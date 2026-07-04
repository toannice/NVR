package vn.aiview.nvr.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.Cameras
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import vn.aiview.nvr.utils.HwaccelDetector
import vn.aiview.nvr.utils.CryptoUtils
import vn.aiview.nvr.services.CameraConfigMutex

private val configMutex = CameraConfigMutex.mutex

@Serializable
data class CameraStatus(val id: String, val name: String, val rtsp: String, val ip: String?, val manufacturer: String, val model: String, val onvifPort: Int = 80, val ptzEnabled: Boolean = false, val ptzMode: String? = null)

@Serializable
data class CameraStats(val isOnline: Boolean, val fps: Double)

fun Route.cameraRouting() {
    get("/api/cameras") {
        try {
            val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
            val configFile = File(configPath)

            // Read go2rtc streams from config.yml for RTSP URL fallback
            @Suppress("UNCHECKED_CAST")
            val go2rtcStreams: Map<String, Any>? = if (configFile.exists()) {
                val yaml = Yaml()
                val configData = yaml.load<Map<String, Any>>(configFile.readText())
                configData?.get("go2rtc").let { it as? Map<String, Any> }?.get("streams") as? Map<String, Any>
            } else null

            fun rtspFromStreams(slug: String): String {
                val streamList = go2rtcStreams?.get(slug) as? List<*> ?: return ""
                val raw = streamList.firstOrNull()?.toString() ?: return ""
                // Skip loopback aliases (those are channelId aliases, not real URLs)
                if (raw.contains("127.0.0.1")) return ""
                return if (raw.contains("#")) raw.substringBefore("#") else raw
            }

            // DB rows are the primary source — they include all cameras (both Web UI added and cloud added).
            val dbRows = transaction { Cameras.selectAll().toList() }
            val dbSlugs = dbRows.map { it[Cameras.slug] }.toSet()

            val cameras = mutableListOf<CameraStatus>()

            // 1. DB rows (includes cameras added from any source)
            for (row in dbRows) {
                val slug = row[Cameras.slug]
                val rtsp = row[Cameras.rtspUrl].ifBlank { rtspFromStreams(slug) }
                var ip = row[Cameras.ip]
                if (ip.isNullOrBlank() || ip == "127.0.0.1") ip = parseIpFromRtsp(rtsp)
                cameras.add(CameraStatus(
                    id = slug,
                    name = row[Cameras.displayName].ifBlank { slug },
                    rtsp = rtsp,
                    ip = ip,
                    manufacturer = row[Cameras.manufacturer],
                    model = row[Cameras.model],
                    onvifPort = row[Cameras.onvifPort],
                    ptzEnabled = row[Cameras.ptzEnabled],
                    ptzMode = row[Cameras.ptzMode]
                ))
            }

            // 2. config.yml-only cameras (edge case: manually added or startup sync not yet run)
            if (configFile.exists()) {
                val yaml = Yaml()
                @Suppress("UNCHECKED_CAST")
                val yamlCameras = (yaml.load<Map<String, Any>>(configFile.readText())
                    ?.get("cameras") as? Map<String, Any>)?.keys ?: emptySet()
                for (slug in yamlCameras) {
                    if (slug in dbSlugs) continue
                    val rtsp = rtspFromStreams(slug)
                    cameras.add(CameraStatus(
                        id = slug,
                        name = slug,
                        rtsp = rtsp,
                        ip = parseIpFromRtsp(rtsp),
                        manufacturer = "Unknown",
                        model = "Unknown"
                    ))
                }
            }

            call.respond(cameras)
        } catch (e: Exception) {
            call.application.environment.log.error("Error listing cameras", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    get("/api/cameras/discover") {
        // Return an empty list or mock data for camera discovery to satisfy frontend requests
        call.respond(emptyList<CameraStatus>())
    }


    post("/api/add-camera") {
        try {
            val jsonStr = call.receiveText()
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            val name = json["name"]?.jsonPrimitive?.content ?: "Unknown"
            val rtspUrl = json["rtspUrl"]?.jsonPrimitive?.content ?: ""
            val recordStr = json["record"]?.jsonPrimitive?.content
            val record = recordStr?.toBoolean() ?: true
            val onvifPort = json["onvifPort"]?.jsonPrimitive?.content?.toIntOrNull() ?: 80
            val ptzEnabled = json["ptzEnabled"]?.jsonPrimitive?.content?.toBoolean() ?: false
            
            val subRtspUrl = json["subRtspUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

            val urlError = validateRtspUrl(rtspUrl)
            if (urlError != null) {
                call.respondText("""{"error":"$urlError"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            if (subRtspUrl != null) {
                val subUrlError = validateRtspUrl(subRtspUrl)
                if (subUrlError != null) {
                    call.respondText("""{"error":"Sub stream: $subUrlError"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            }

            val ipAddr = parseIpFromRtsp(rtspUrl) ?: ""
            val slug = "${name.lowercase().replace(Regex("[^a-z0-9]+"), "_")}_${ipAddr.replace(".", "_")}"

            val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
            val existingInDb = transaction {
                Cameras.selectAll().where { Cameras.slug eq slug }.firstOrNull()
            }
            if (existingInDb != null) {
                val configFile = File(configPath)
                val alreadyInYaml = if (configFile.exists()) {
                    val yaml = Yaml()
                    val cur = yaml.load<Map<String, Any>>(configFile.readText())
                    (cur?.get("cameras") as? Map<String, Any>)?.containsKey(slug) == true
                } else false
                if (alreadyInYaml) {
                    call.respondText("""{"error":"Camera với Tên hoặc IP này đã tồn tại"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            }

            val applied = vn.aiview.nvr.services.CameraService.applyAndPersistFromRtsp(
                slug = slug,
                displayName = name,
                rtspUrl = rtspUrl,
                subRtspUrl = subRtspUrl,
                ip = ipAddr,
                onvifPort = onvifPort,
                ptzEnabled = ptzEnabled,
                record = record,
            ).getOrElse {
                call.application.environment.log.error("CameraService.applyAndPersistFromRtsp failed", it)
                call.respondText("""{"error":"${it.message}"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
                return@post
            }

            // Notify cloud immediately so camera list syncs without waiting for the 5-second heartbeat
            vn.aiview.nvr.cloud.SignalingManager.triggerChannelStateNotification()
            call.respondText(
                """{"success":true,"frigateRestarted":${applied.frigateRestarted},"message":"Camera added successfully"}""",
                io.ktor.http.ContentType.Application.Json
            )
        } catch (e: Exception) {
            call.application.environment.log.error("Error adding camera", e)
            call.respondText("""{"error":"${e.message}"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
        }
    }

    post("/api/edit-camera") {
        try {
            val jsonStr = call.receiveText()
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            val id = json["id"]?.jsonPrimitive?.content
                ?: run {
                    call.respondText("""{"error":"Camera ID is required"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            val newName = json["name"]?.jsonPrimitive?.content?.trim()
                ?: run {
                    call.respondText("""{"error":"Name is required"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            if (newName.isBlank()) {
                call.respondText("""{"error":"Name cannot be blank"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            // Optional: ONVIF port (e.g. set to the socat proxy port so PTZ reaches a proxied camera).
            val onvifPort = json["onvifPort"]?.jsonPrimitive?.content?.toIntOrNull()
            if (onvifPort != null && (onvifPort < 1 || onvifPort > 65535)) {
                call.respondText("""{"error":"Invalid ONVIF port"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            // Optional: ONVIF credentials — needed for PTZ on proxy/cloud cameras whose RTSP URL
            // carries no embedded user:pass. Stored as plaintext username + encrypted password.
            val onvifUser = json["onvifUsername"]?.jsonPrimitive?.content?.trim()
            val onvifPass = json["onvifPassword"]?.jsonPrimitive?.content
            // PTZ capability override: "auto" (null) | "full" | "zoom". Lets the user force the
            // pan/tilt d-pad for a real PTZ camera whose ONVIF GetNodes only advertises Zoom.
            val ptzModeRaw = json["ptzMode"]?.jsonPrimitive?.content?.trim()?.lowercase()
            val ptzMode: String? = when (ptzModeRaw) {
                "full", "zoom" -> ptzModeRaw
                "auto", "" -> null
                else -> ptzModeRaw  // null stays null; unknown values are ignored below
            }
            val updated = transaction {
                Cameras.update({ Cameras.slug eq id }) {
                    it[Cameras.displayName] = newName
                    if (onvifPort != null) it[Cameras.onvifPort] = onvifPort
                    if (!onvifUser.isNullOrBlank()) it[Cameras.username] = onvifUser
                    // Only overwrite the password when a non-blank one is supplied, so editing
                    // name/port alone never wipes saved credentials.
                    if (!onvifPass.isNullOrBlank()) it[Cameras.passwordEncrypted] = CryptoUtils.encrypt(onvifPass)
                    // Persist the override whenever the client sent the field (incl. "auto" -> null).
                    // Forcing an explicit full/zoom also turns PTZ on, so the canonical 3-state stays
                    // coherent (mode full/zoom ⇒ enabled). "auto"/null leaves the on/off flag alone.
                    if (ptzModeRaw != null) {
                        it[Cameras.ptzMode] = ptzMode
                        if (ptzMode == "full" || ptzMode == "zoom") {
                            it[Cameras.ptzEnabled] = true
                            it[Cameras.hasPtz] = true
                        }
                    }
                }
            }
            if (updated == 0) {
                call.respondText("""{"error":"Camera not found"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }

            // Optional: attach/replace the sub-stream (e.g. an ONVIF 2nd profile that wasn't stored
            // at add time). Persisted through the unified path → dual-stream YAML + go2rtc {ch}_sub.
            val subRtspUrl = json["subRtspUrl"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
            if (subRtspUrl != null) {
                val subUrlError = validateRtspUrl(subRtspUrl)
                if (subUrlError != null) {
                    call.respondText("""{"error":"Sub stream: $subUrlError"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
                vn.aiview.nvr.services.CameraService.setSubStream(id, subRtspUrl).getOrElse {
                    call.application.environment.log.error("CameraService.setSubStream failed", it)
                    call.respondText("""{"error":"${it.message ?: "Failed to set sub stream"}"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    return@post
                }
            }

            call.respondText("""{"success":true,"message":"Đã cập nhật camera"}""", io.ktor.http.ContentType.Application.Json)
        } catch (e: Exception) {
            call.application.environment.log.error("Error renaming camera", e)
            call.respondText("""{"error":"${e.message}"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
        }
    }

    post("/api/delete-camera") {
        try {
            val jsonStr = call.receiveText()
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            val cameraId = json["name"]?.jsonPrimitive?.content
                ?: json["id"]?.jsonPrimitive?.content
                ?: run {
                    call.respondText("""{"error":"Camera ID is required"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }

            val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
            val file = File(configPath)

            if (file.exists()) {
                vn.aiview.nvr.services.CameraService.removeAndPersist(cameraId).getOrElse {
                    call.application.environment.log.error("CameraService.removeAndPersist failed", it)
                    call.respondText("""{"error":"${it.message}"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    return@post
                }
                // Notify cloud immediately so camera list syncs without waiting for the 5-second heartbeat
                vn.aiview.nvr.cloud.SignalingManager.triggerChannelStateNotification()
                call.respondText("""{"success":true,"removed":true}""", io.ktor.http.ContentType.Application.Json)
            } else {
                call.respondText("""{"error":"Config file not found"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.NotFound)
            }
        } catch (e: Exception) {
            call.application.environment.log.error("Error deleting camera", e)
            call.respondText("""{"error":"${e.message}"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
        }
    }

    get("/api/camera-status") {
        try {
            val frigateUrl = System.getenv("FRIGATE_URL") ?: "http://localhost:5000"
            val url = java.net.URL("$frigateUrl/api/stats")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            
            if (conn.responseCode == 200) {
                val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = Json.parseToJsonElement(responseStr).jsonObject
                val camerasObj = root["cameras"]?.jsonObject
                
                val statusMap = mutableMapOf<String, CameraStats>()
                camerasObj?.forEach { (camName, camData) ->
                    val fps = camData.jsonObject["camera_fps"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                    statusMap[camName] = CameraStats(
                        isOnline = (fps > 0),
                        fps = fps
                    )
                }
                call.respond(statusMap)
            } else {
                call.respondText("""{}""", io.ktor.http.ContentType.Application.Json)
            }
        } catch (e: Exception) {
            call.application.environment.log.error("Error fetching camera status from Frigate", e)
            call.respondText("""{}""", io.ktor.http.ContentType.Application.Json)
        }
    }

    get("/api/cameras/{id}/ai") {
        try {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
            val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
            val file = File(configPath)
            if (!file.exists()) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config file not found"))
                return@get
            }
            val yaml = Yaml()
            val configData = yaml.load<Map<String, Any>>(file.readText())
            val cameras = configData["cameras"] as? Map<String, Any> ?: emptyMap()
            val camConfig = cameras[id] as? Map<String, Any>
            if (camConfig == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Camera not found"))
                return@get
            }
            val detect = camConfig["detect"] as? Map<String, Any>
            val enabled = detect?.get("enabled")?.toString()?.toBoolean() ?: false
            
            val zonesRaw = camConfig["zones"] as? Map<String, Any> ?: emptyMap()
            
            val responseJson = buildJsonObject {
                put("enabled", enabled)
                putJsonObject("zones") {
                    zonesRaw.forEach { (zName, zDataRaw) ->
                        val zData = zDataRaw as? Map<String, Any> ?: emptyMap()
                        putJsonObject(zName) {
                            put("coordinates", zData["coordinates"]?.toString() ?: "")
                            put("objects", buildJsonArray {
                                (zData["objects"] as? List<String> ?: emptyList()).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                            })
                        }
                    }
                }
            }
            
            call.respondText(responseJson.toString(), io.ktor.http.ContentType.Application.Json)
        } catch (e: Exception) {
            call.application.environment.log.error("Error getting camera AI status", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    post("/api/cameras/{id}/ai") {
        try {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
            
            val jsonStr = call.receiveText()
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            val enabled = json["enabled"]?.jsonPrimitive?.content?.toBoolean() ?: false
            
            val zonesJson = json["zones"]?.jsonObject
            val zonesMap = mutableMapOf<String, Any>()
            zonesJson?.forEach { (zName, zDataElem) ->
                val zData = zDataElem.jsonObject
                val coords = zData["coordinates"]?.jsonPrimitive?.content ?: ""
                val objs = zData["objects"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList<String>()
                
                val zoneConfig = mutableMapOf<String, Any>("coordinates" to coords)
                if (objs.isNotEmpty()) {
                    zoneConfig["objects"] = objs
                }
                zonesMap[zName] = zoneConfig
            }

            val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
            val file = File(configPath)
            if (!file.exists()) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config file not found"))
                return@post
            }

            configMutex.withLock {
                val yaml = Yaml()
                val configData = yaml.load<MutableMap<String, Any>>(file.readText())
                val cameras = configData["cameras"] as? MutableMap<String, Any>
                if (cameras == null || cameras[id] == null) return@withLock

                val camConfig = cameras[id] as MutableMap<String, Any>

                val allObjects = mutableSetOf<String>()
                val zoneNames = zonesMap.keys.toList()
                zonesMap.values.forEach { zData ->
                    val zMap = zData as? Map<String, Any>
                    val objs = zMap?.get("objects") as? List<String>
                    objs?.let { allObjects.addAll(it) }
                }

                val detectRaw = camConfig["detect"] as? Map<*, *>
                val detect = if (detectRaw != null) (detectRaw as Map<String, Any>).toMutableMap() else mutableMapOf()

                val finalEnabled = enabled && allObjects.isNotEmpty()
                detect["enabled"] = finalEnabled
                camConfig["detect"] = detect

                val reviewRaw = camConfig["review"] as? Map<*, *>
                val review = if (reviewRaw != null) (reviewRaw as Map<String, Any>).toMutableMap() else mutableMapOf()
                val alertsRaw = review["alerts"] as? Map<*, *>
                val alerts = if (alertsRaw != null) (alertsRaw as Map<String, Any>).toMutableMap() else mutableMapOf()

                if (zonesMap.isEmpty() || allObjects.isEmpty()) {
                    camConfig.remove("zones")
                    alerts.remove("required_zones")
                } else {
                    camConfig["zones"] = zonesMap
                    alerts["required_zones"] = zoneNames
                }

                if (alerts.isEmpty()) review.remove("alerts") else review["alerts"] = alerts
                if (review.isEmpty()) camConfig.remove("review") else camConfig["review"] = review

                if (allObjects.isNotEmpty()) {
                    val objectsRaw = camConfig["objects"] as? Map<*, *>
                    val objects = if (objectsRaw != null) (objectsRaw as Map<String, Any>).toMutableMap() else mutableMapOf()
                    objects["track"] = allObjects.toList()
                    camConfig["objects"] = objects
                } else {
                    camConfig.remove("objects")
                }

                val ffmpegRaw = camConfig["ffmpeg"] as? Map<*, *>
                val ffmpeg = if (ffmpegRaw != null) (ffmpegRaw as Map<String, Any>).toMutableMap() else mutableMapOf()

                val inputsRaw = ffmpeg["inputs"] as? List<*>
                if (inputsRaw != null && inputsRaw.isNotEmpty()) {
                    val inputs = inputsRaw.toMutableList()
                    if (finalEnabled) {
                        // Add detect to sub-stream input if present, otherwise to first input
                        val targetIdx = inputs.indexOfFirst {
                            (it as? Map<*, *>)?.get("path")?.toString()?.contains("_sub") == true
                        }.let { if (it >= 0) it else 0 }
                        val targetRaw = inputs[targetIdx] as? Map<*, *>
                        if (targetRaw != null) {
                            val input = (targetRaw as Map<String, Any>).toMutableMap()
                            val roles = ((input["roles"] as? List<*>)?.map { it.toString() } ?: emptyList()).toMutableList()
                            if (!roles.contains("detect")) roles.add("detect")
                            input["roles"] = roles
                            inputs[targetIdx] = input
                        }
                    } else {
                        // Remove detect from all inputs
                        inputs.forEachIndexed { idx, inputRaw ->
                            val inputMap = (inputRaw as? Map<*, *>) ?: return@forEachIndexed
                            @Suppress("UNCHECKED_CAST")
                            val input = (inputMap as Map<String, Any>).toMutableMap()
                            val roles = ((input["roles"] as? List<*>)?.map { it.toString() } ?: emptyList()).toMutableList()
                            if (roles.remove("detect")) {
                                input["roles"] = roles
                                inputs[idx] = input
                            }
                        }
                    }
                    ffmpeg["inputs"] = inputs
                }
                camConfig["ffmpeg"] = ffmpeg

                val options = org.yaml.snakeyaml.DumperOptions().apply {
                    defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
                }
                java.io.FileWriter(file).use { writer ->
                    Yaml(options).dump(configData, writer)
                }
            }

            // NOTE: No auto-restart here — user must call /ai/apply to restart Frigate
            call.respond(mapOf("success" to true, "enabled" to enabled))
        } catch (e: Exception) {
            call.application.environment.log.error("Error setting camera AI status", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    // Explicitly restart Frigate to apply new config.
    // Frontend calls this separately so Save and Restart are decoupled.
    post("/api/cameras/{id}/ai/apply") {
        try {
            val frigateUrl = System.getenv("FRIGATE_URL") ?: "http://localhost:5000"
            try {
                val restartUrl = java.net.URL("$frigateUrl/api/restart")
                val conn = restartUrl.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.responseCode
                call.application.environment.log.info("Frigate restart triggered via /apply")
            } catch (e: Exception) {
                // Expected: Frigate closes connection when it restarts
                call.application.environment.log.info("Frigate restart request sent (${e.message})")
            }
            call.respond(mapOf("success" to "true", "message" to "Frigate restart triggered"))
        } catch (e: Exception) {
            call.application.environment.log.error("Error in /ai/apply", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    // Global toggle: disable Frigate's own object detection across ALL cameras at once, to save
    // CPU/GPU when the customer relies on an external Box AI for detection instead. Independent
    // from the per-camera zone editor above — writes the top-level `detect.enabled` plus forces
    // every camera's own `detect.enabled` override (Frigate lets a camera override the global key,
    // so leaving it untouched would keep detecting on cameras that have zones configured).
    get("/api/settings/detect") {
        try {
            val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
            val file = File(configPath)
            if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config file not found"))
            val yaml = Yaml()
            val configData = yaml.load<Map<String, Any>>(file.readText()) ?: emptyMap()
            val detect = configData["detect"] as? Map<String, Any>
            val enabled = detect?.get("enabled")?.toString()?.toBoolean() ?: true
            call.respond(mapOf("enabled" to enabled))
        } catch (e: Exception) {
            call.application.environment.log.error("Error getting global detect status", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    post("/api/settings/detect") {
        try {
            val jsonStr = call.receiveText()
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            val enabled = json["enabled"]?.jsonPrimitive?.content?.toBoolean() ?: true

            val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
            val file = File(configPath)
            if (!file.exists()) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config file not found"))

            configMutex.withLock {
                val yaml = Yaml()
                val configData = yaml.load<MutableMap<String, Any>>(file.readText()) ?: mutableMapOf()
                configData["detect"] = mapOf("enabled" to enabled)

                @Suppress("UNCHECKED_CAST")
                val cameras = configData["cameras"] as? Map<String, Any>
                cameras?.values?.forEach { camDataRaw ->
                    @Suppress("UNCHECKED_CAST")
                    val camConfig = camDataRaw as? MutableMap<String, Any> ?: return@forEach
                    val detectRaw = camConfig["detect"] as? Map<*, *>
                    val detect = if (detectRaw != null) (detectRaw as Map<String, Any>).toMutableMap() else mutableMapOf()
                    detect["enabled"] = enabled
                    camConfig["detect"] = detect
                }

                val options = org.yaml.snakeyaml.DumperOptions().apply {
                    defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
                }
                java.io.FileWriter(file).use { writer -> Yaml(options).dump(configData, writer) }
            }

            call.respond(mapOf("success" to true, "enabled" to enabled))
        } catch (e: Exception) {
            call.application.environment.log.error("Error setting global detect status", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    // Explicitly restart Frigate to apply the global detect toggle (decoupled from save, same
    // pattern as /api/cameras/{id}/ai/apply above — frontend polls /api/frigate/api/version after).
    post("/api/settings/detect/apply") {
        try {
            val frigateUrl = System.getenv("FRIGATE_URL") ?: "http://localhost:5000"
            try {
                val restartUrl = java.net.URL("$frigateUrl/api/restart")
                val conn = restartUrl.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.responseCode
                call.application.environment.log.info("Frigate restart triggered via /settings/detect/apply")
            } catch (e: Exception) {
                // Expected: Frigate closes the connection as part of restarting.
                call.application.environment.log.info("Frigate restart request sent (${e.message})")
            }
            call.respond(mapOf("success" to "true", "message" to "Frigate restart triggered"))
        } catch (e: Exception) {
            call.application.environment.log.error("Error in /settings/detect/apply", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    patch("/api/cameras/{id}/ptz-enabled") {
        val id = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
        try {
            val jsonStr = call.receiveText()
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            // Canonical 3-state classification: "none" | "zoom" | "full".
            // Legacy boolean { ptzEnabled } is still accepted (old on/off toggle): true→full, false→none.
            val modeRaw = json["mode"]?.jsonPrimitive?.content?.trim()?.lowercase()
            val (enabled, mode) = when (modeRaw) {
                "none" -> false to "none"
                "zoom" -> true to "zoom"
                "full" -> true to "full"
                else -> {
                    val en = json["ptzEnabled"]?.jsonPrimitive?.content?.toBoolean() ?: false
                    en to (if (en) "full" else "none")
                }
            }
            val updated = transaction {
                Cameras.update({ Cameras.slug eq id }) {
                    it[Cameras.ptzEnabled] = enabled
                    it[Cameras.hasPtz] = enabled
                    it[Cameras.ptzMode] = mode
                }
            }
            if (updated > 0) {
                call.respondText(
                    """{"success":true,"ptzEnabled":$enabled,"mode":"$mode"}""",
                    io.ktor.http.ContentType.Application.Json
                )
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Camera not found"))
            }
        } catch (e: Exception) {
            call.application.environment.log.error("Error updating PTZ flag for $id", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    // Debug / convenience endpoint: proxy Frigate recordings for a camera
    get("/api/cameras/{id}/recordings") {
        try {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
            val after = call.request.queryParameters["after"] ?: ""
            val before = call.request.queryParameters["before"] ?: ""
            val frigateUrl = System.getenv("FRIGATE_URL") ?: "http://localhost:5000"
            val targetUrl = "$frigateUrl/api/$id/recordings?after=$after&before=$before"

            call.application.environment.log.info("Fetching recordings from: $targetUrl")
            val url = java.net.URL(targetUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 10000

            if (conn.responseCode == 200) {
                val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                call.application.environment.log.info("Frigate recordings response (first 500 chars): ${responseStr.take(500)}")
                call.respondText(responseStr, io.ktor.http.ContentType.Application.Json)
            } else {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Empty error body"
                call.application.environment.log.warn("Frigate recordings returned ${conn.responseCode}: $errBody")
                call.respond(HttpStatusCode.fromValue(conn.responseCode), mapOf("error" to "Frigate returned ${conn.responseCode}", "body" to errBody))
            }
        } catch (e: Exception) {
            call.application.environment.log.error("Error fetching recordings", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }
}


private fun validateRtspUrl(url: String): String? {
    if (url.isBlank()) return "RTSP URL không được để trống"
    val lower = url.lowercase()
    if (!lower.startsWith("rtsp://") && !lower.startsWith("rtsps://")) {
        return "URL phải bắt đầu bằng rtsp:// hoặc rtsps://"
    }
    val host = url.substringAfter("//").substringAfterLast("@").substringBefore("/").substringBefore(":").lowercase()
    val blockedHosts = setOf("localhost", "host.docker.internal")
    if (host in blockedHosts) return "URL trỏ đến host bị cấm"
    if (host == "127.0.0.1" || host.startsWith("127.")) return "URL trỏ đến loopback bị cấm"
    if (host.startsWith("169.254.")) return "URL trỏ đến địa chỉ bị cấm"
    return null
}

private fun parseIpFromRtsp(rtsp: String): String? {
    try {
        if (rtsp.isBlank()) return null
        // More robust parsing: find part after // and before /
        val hostPart = rtsp.substringAfter("//").substringBefore("/")
        // Part after @ is host[:port]
        val hostWithPort = hostPart.substringAfterLast("@")
        // Just the host (IP)
        return hostWithPort.substringBefore(":")
    } catch (e: Exception) {
        return null
    }
}
