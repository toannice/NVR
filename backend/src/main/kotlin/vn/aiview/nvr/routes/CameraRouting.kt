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
import vn.aiview.nvr.services.CameraConfigMutex

private val configMutex = CameraConfigMutex.mutex

@Serializable
data class CameraStatus(val id: String, val name: String, val rtsp: String, val ip: String?, val manufacturer: String, val model: String, val onvifPort: Int = 80, val ptzEnabled: Boolean = false)

@Serializable
data class CameraStats(val isOnline: Boolean, val fps: Double)

fun Route.cameraRouting() {
    get("/api/cameras") {
        try {
            val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
            val configFile = File(configPath)
            if (!configFile.exists()) {
                call.respond(emptyList<CameraStatus>())
                return@get
            }
            
            val yaml = Yaml()
            val configData = yaml.load<Map<String, Any>>(configFile.readText())
            val cameraConfigs = configData["cameras"] as? Map<String, Any> ?: emptyMap()
            
            val go2rtc = configData["go2rtc"] as? Map<String, Any>
            val streams = go2rtc?.get("streams") as? Map<String, Any>
            
            val dbCamerasRow = transaction {
                Cameras.selectAll().associateBy({ it[Cameras.slug] }, { it })
            }
            
            val cameras = cameraConfigs.keys.map { slug ->
                val camConfig = cameraConfigs[slug] as? Map<String, Any>
                val ffmpeg = camConfig?.get("ffmpeg") as? Map<String, Any>
                val inputs = ffmpeg?.get("inputs") as? List<Map<String, Any>>
                val rtspPath = inputs?.firstOrNull()?.get("path")?.toString() ?: ""
                
                val streamList = streams?.get(slug) as? List<*>
                val streamUrl = streamList?.firstOrNull()?.toString() ?: ""
                val originalRtspUrl = if (streamUrl.endsWith("#tcp") || streamUrl.endsWith("#udp")) {
                    streamUrl.substringBeforeLast("#")
                } else {
                    streamUrl
                }.ifEmpty { rtspPath }
                
                val dbRecord = dbCamerasRow[slug]
                var ip = dbRecord?.get(Cameras.ip)
                if (ip.isNullOrBlank() || ip == "127.0.0.1") {
                    ip = parseIpFromRtsp(originalRtspUrl)
                }
                
                CameraStatus(
                    id = slug,
                    name = dbRecord?.get(Cameras.displayName) ?: slug,
                    rtsp = originalRtspUrl,
                    ip = ip,
                    manufacturer = dbRecord?.get(Cameras.manufacturer) ?: "Unknown",
                    model = dbRecord?.get(Cameras.model) ?: "Unknown",
                    onvifPort = dbRecord?.get(Cameras.onvifPort) ?: 80,
                    ptzEnabled = dbRecord?.get(Cameras.ptzEnabled) ?: false
                )
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
            
            val urlError = validateRtspUrl(rtspUrl)
            if (urlError != null) {
                call.respondText("""{"error":"$urlError"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
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
                ip = ipAddr,
                onvifPort = onvifPort,
                ptzEnabled = ptzEnabled,
                record = record,
            ).getOrElse {
                call.application.environment.log.error("CameraService.applyAndPersistFromRtsp failed", it)
                call.respondText("""{"error":"${it.message}"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
                return@post
            }

            call.respondText(
                """{"success":true,"frigateRestarted":${applied.frigateRestarted},"message":"Camera added successfully"}""",
                io.ktor.http.ContentType.Application.Json
            )
        } catch (e: Exception) {
            call.application.environment.log.error("Error adding camera", e)
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
                if (review.isEmpty()) camConfig.remove("review") else review["review"] = review

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
                    val firstInputRaw = inputs[0] as? Map<*, *>
                    if (firstInputRaw != null) {
                        val firstInput = (firstInputRaw as Map<String, Any>).toMutableMap()
                        val rolesRaw = firstInput["roles"] as? List<*>
                        val roles = rolesRaw?.map { it.toString() }?.toMutableList() ?: mutableListOf()

                        if (finalEnabled) {
                            if (!roles.contains("detect")) roles.add("detect")
                        } else {
                            roles.remove("detect")
                        }

                        firstInput["roles"] = roles
                        inputs[0] = firstInput
                        ffmpeg["inputs"] = inputs
                    }
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

    patch("/api/cameras/{id}/ptz-enabled") {
        val id = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
        try {
            val jsonStr = call.receiveText()
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            val enabled = json["ptzEnabled"]?.jsonPrimitive?.content?.toBoolean() ?: false
            val updated = transaction {
                Cameras.update({ Cameras.slug eq id }) {
                    it[Cameras.ptzEnabled] = enabled
                    it[Cameras.hasPtz] = enabled
                }
            }
            if (updated > 0) {
                call.respond(mapOf("success" to true, "ptzEnabled" to enabled))
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
