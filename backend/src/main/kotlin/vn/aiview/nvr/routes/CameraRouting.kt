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
import vn.aiview.nvr.utils.AppLogger
import vn.aiview.nvr.services.RtspTransport
import vn.aiview.nvr.services.CameraService

private val configMutex = CameraConfigMutex.mutex

@Serializable
data class CameraStatus(val id: String, val name: String, val rtsp: String, val ip: String?, val manufacturer: String, val model: String, val onvifPort: Int = 80, val ptzEnabled: Boolean = false, val ptzMode: String? = null)

@Serializable
data class CameraStats(val isOnline: Boolean, val fps: Double)

// Ktor không serialize được Map có giá trị khác kiểu nhau ("Serializing collections of different
// element types is not yet supported") — lỗi chỉ lộ lúc chạy, không phải lúc biên dịch. Mọi
// response trộn Boolean/String/List phải đi qua data class.
@Serializable
data class ApplyResponse(val success: Boolean, val rolledBack: Boolean, val message: String)

@Serializable
data class GlobalDetectResponse(
    val success: Boolean,
    val enabled: Boolean,
    /** Camera chưa ai cấu hình AI — frontend hỏi có cấp vùng Toàn cảnh không. */
    val camerasNeedingDefaultZone: List<String>,
)

@Serializable
data class DefaultZonesResponse(val success: Boolean, val applied: Int, val cameras: List<String>)

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

            fun maskRtspCredentials(rtsp: String): String {
                if (rtsp.isBlank()) return rtsp
                return Regex("://([^:@/]+):([^@/]+)@").replace(rtsp) { m -> "://${m.groupValues[1]}:****@" }
            }

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
                    rtsp = maskRtspCredentials(rtsp),
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
                        rtsp = maskRtspCredentials(rtsp),
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
            
            val globalEnabled = (configData["detect"] as? Map<*, *>)
                ?.get("enabled")?.toString()?.toBoolean() ?: true
            val state = vn.aiview.nvr.services.AiConfigState

            val responseJson = buildJsonObject {
                put("enabled", enabled)
                // AI tổng tắt thì frontend chặn cấu hình từng camera — sửa vùng lúc đó chỉ gây
                // hiểu nhầm là AI đang chạy.
                put("globalEnabled", globalEnabled)
                // Vùng Toàn cảnh còn nguyên trạng hệ thống cấp → vùng người dùng vẽ đầu tiên được
                // phép thay thế nó. Người dùng đã sửa thì không.
                put("defaultZoneUntouched", state.isDefaultZoneUntouched(id))
                put("pendingApply", state.isDirty())
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
                val coords = sanitizeZoneCoordinates(zData["coordinates"]?.jsonPrimitive?.content ?: "")
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

            // Chặn tên vùng mà Frigate sẽ từ chối. Đã test trực tiếp với validator của Frigate
            // 0.16.3: tiếng Việt có dấu, khoảng trắng, gạch ngang đều OK — nhưng vùng TRÙNG TÊN
            // CAMERA thì "Zones cannot share names with cameras" → config invalid → crash-loop.
            // Bắt ở đây chứ không để tới lúc restart, vì lúc đó là đã ghi đè file rồi.
            validateZoneNames(zonesMap.keys, file)?.let { err ->
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to err))
                return@post
            }

            var savedEnabled = false
            configMutex.withLock {
                vn.aiview.nvr.services.FrigateConfigGuard.snapshot(file)
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
                savedEnabled = finalEnabled

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
                    // Frigate luôn đòi ĐÚNG một input mang role `detect`, kể cả khi
                    // `detect.enabled: false`. Bật/tắt AI chỉ đổi `detect.enabled` —
                    // gỡ role ra sẽ khiến config invalid → frigate crash-loop.
                    val targetIdx = inputs.indexOfFirst {
                        (it as? Map<*, *>)?.get("path")?.toString()?.contains("_sub") == true
                    }.let { if (it >= 0) it else 0 }
                    inputs.forEachIndexed { idx, inputRaw ->
                        val inputMap = (inputRaw as? Map<*, *>) ?: return@forEachIndexed
                        @Suppress("UNCHECKED_CAST")
                        val input = (inputMap as Map<String, Any>).toMutableMap()
                        val roles = ((input["roles"] as? List<*>)?.map { it.toString() } ?: emptyList()).toMutableList()
                        if (idx == targetIdx) {
                            if (!roles.contains("detect")) roles.add("detect")
                        } else {
                            roles.remove("detect")
                        }
                        input["roles"] = roles
                        inputs[idx] = input
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

            // Ghi lại ý định của người dùng. Xoá hết vùng = CỐ Ý tắt camera này, khác hẳn với
            // camera chưa ai cấu hình — lần bật AI tổng sau sẽ tôn trọng lựa chọn này thay vì
            // âm thầm cấp lại vùng Toàn cảnh.
            val state = vn.aiview.nvr.services.AiConfigState
            state.setIntent(id, if (savedEnabled) state.INTENT_ON else state.INTENT_OFF)
            // Vùng nào cũng đã do người dùng quyết → vùng Toàn cảnh (nếu còn) không còn là bản
            // mặc định hệ thống cấp nữa, không được tự xoá nó ở lần vẽ vùng sau.
            if (savedEnabled) state.setDefaultZoneFlag(id, false)

            // Không restart ở đây: người dùng bấm "Áp dụng" để gộp mọi thay đổi vào MỘT lần
            // restart, thay vì mỗi camera một lần (mỗi lần ~1 phút thủng bản ghi).
            state.markDirty()
            call.respond(mapOf("success" to true, "enabled" to savedEnabled, "pendingApply" to true))
        } catch (e: Exception) {
            call.application.environment.log.error("Error setting camera AI status", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    // Áp dụng cấu hình: restart Frigate rồi CHỜ xác nhận nó khoẻ lại. Không khoẻ → tự khôi phục
    // bản config trước đó (FrigateConfigGuard) để máy không nằm im ở trạng thái crash-loop.
    // Tách khỏi bước Lưu để nhiều thay đổi chỉ tốn một lần restart.
    post("/api/cameras/{id}/ai/apply") {
        try {
            val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
            val result = vn.aiview.nvr.services.FrigateConfigGuard
                .restartAndVerify(File(configPath), "ai_config_apply")
            if (result.ok) vn.aiview.nvr.services.AiConfigState.clearDirty()
            call.respond(ApplyResponse(result.ok, result.rolledBack, result.message))
        } catch (e: Exception) {
            call.application.environment.log.error("Error in /ai/apply", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    // Công tắc tổng AI View NVR: bật/tắt nhận diện trên TOÀN BỘ camera một lần, để nhường CPU/GPU
    // khi khách đã có Box AI ngoài lo phần nhận diện. Phải ép `detect.enabled` cho từng camera chứ
    // không chỉ key global, vì Frigate cho camera override giá trị global.
    //
    // TẮT không xoá vùng: người dùng vẽ vùng mất hàng giờ, tắt AI thường chỉ là tạm thời. Tắt =
    // vô hiệu hoá, bật lại = nguyên trạng. Muốn xoá thì có đường riêng, có cảnh báo.
    get("/api/settings/detect") {
        try {
            val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
            val file = File(configPath)
            if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config file not found"))
            val yaml = Yaml()
            val configData = yaml.load<Map<String, Any>>(file.readText()) ?: emptyMap()
            val detect = configData["detect"] as? Map<String, Any>
            val enabled = detect?.get("enabled")?.toString()?.toBoolean() ?: true
            call.respond(mapOf(
                "enabled" to enabled,
                "pendingApply" to vn.aiview.nvr.services.AiConfigState.isDirty(),
            ))
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

            val state = vn.aiview.nvr.services.AiConfigState
            // Camera chưa ai cấu hình AI bao giờ — frontend sẽ hỏi có cấp vùng Toàn cảnh không.
            val needZone = mutableListOf<String>()

            configMutex.withLock {
                vn.aiview.nvr.services.FrigateConfigGuard.snapshot(file)
                val yaml = Yaml()
                val configData = yaml.load<MutableMap<String, Any>>(file.readText()) ?: mutableMapOf()
                configData["detect"] = mapOf("enabled" to enabled)

                @Suppress("UNCHECKED_CAST")
                val cameras = configData["cameras"] as? Map<String, Any>
                cameras?.forEach { (slug, camDataRaw) ->
                    @Suppress("UNCHECKED_CAST")
                    val camConfig = camDataRaw as? MutableMap<String, Any> ?: return@forEach
                    val detectRaw = camConfig["detect"] as? Map<*, *>
                    val detect = if (detectRaw != null) (detectRaw as Map<String, Any>).toMutableMap() else mutableMapOf()
                    val hasZones = (camConfig["zones"] as? Map<*, *>)?.isNotEmpty() == true

                    detect["enabled"] = if (!enabled) {
                        // Tắt tổng: tắt sạch, nhưng KHÔNG đụng zones và KHÔNG đụng ai_intent —
                        // bật lại là mọi thứ về đúng như cũ.
                        false
                    } else {
                        // Bật tổng: khôi phục theo ý định đã lưu, không bật bừa.
                        when (state.intentOf(slug)) {
                            // Cố ý tắt riêng trước đó → giữ tắt. Bật tổng không được phép âm thầm
                            // đảo ngược lựa chọn của người dùng.
                            state.INTENT_OFF -> false
                            // Có vùng thì bật lại đúng nguyên trạng.
                            state.INTENT_ON -> hasZones
                            // Chưa cấu hình bao giờ: chỉ bật nếu đã có sẵn vùng; không thì gom
                            // vào danh sách để hỏi người dùng có muốn cấp vùng Toàn cảnh không.
                            else -> {
                                if (!hasZones) needZone.add(slug)
                                hasZones
                            }
                        }
                    }
                    camConfig["detect"] = detect
                }

                val options = org.yaml.snakeyaml.DumperOptions().apply {
                    defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
                }
                java.io.FileWriter(file).use { writer -> Yaml(options).dump(configData, writer) }
            }

            state.markDirty()
            // Frontend dùng needZone để bật dialog "Cấp vùng Toàn cảnh cho N camera này?"
            call.respond(GlobalDetectResponse(true, enabled, needZone))
        } catch (e: Exception) {
            call.application.environment.log.error("Error setting global detect status", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    // Cấp vùng "Toàn cảnh" mặc định cho nhiều camera trong MỘT lần ghi. Gọi sau khi người dùng
    // đồng ý ở dialog lúc bật AI tổng — làm từng camera một sẽ tốn từng đó lần restart.
    post("/api/settings/detect/default-zones") {
        try {
            val jsonStr = call.receiveText()
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            val slugs = json["cameras"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            if (slugs.isEmpty()) return@post call.respond(DefaultZonesResponse(true, 0, emptyList()))

            val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
            val file = File(configPath)
            if (!file.exists()) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config file not found"))

            val state = vn.aiview.nvr.services.AiConfigState
            val applied = mutableListOf<String>()

            configMutex.withLock {
                vn.aiview.nvr.services.FrigateConfigGuard.snapshot(file)
                val yaml = Yaml()
                val configData = yaml.load<MutableMap<String, Any>>(file.readText()) ?: mutableMapOf()
                @Suppress("UNCHECKED_CAST")
                val cameras = configData["cameras"] as? Map<String, Any> ?: emptyMap()

                slugs.forEach { slug ->
                    @Suppress("UNCHECKED_CAST")
                    val camConfig = cameras[slug] as? MutableMap<String, Any> ?: return@forEach
                    // Vùng mặc định không được đè lên vùng người dùng đã có.
                    if ((camConfig["zones"] as? Map<*, *>)?.isNotEmpty() == true) return@forEach

                    camConfig["zones"] = state.defaultZoneBlock()
                    camConfig["detect"] = mapOf("enabled" to true)
                    camConfig["objects"] = mapOf("track" to state.DEFAULT_ZONE_OBJECTS)
                    val reviewRaw = camConfig["review"] as? Map<*, *>
                    val review = if (reviewRaw != null) (reviewRaw as Map<String, Any>).toMutableMap() else mutableMapOf()
                    review["alerts"] = mapOf("required_zones" to listOf(state.DEFAULT_ZONE_NAME))
                    camConfig["review"] = review
                    applied.add(slug)
                }

                val options = org.yaml.snakeyaml.DumperOptions().apply {
                    defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
                }
                java.io.FileWriter(file).use { writer -> Yaml(options).dump(configData, writer) }
            }

            applied.forEach { slug ->
                state.setIntent(slug, state.INTENT_ON)
                // Đánh dấu đây là vùng hệ thống cấp: vùng đầu tiên người dùng tự vẽ được phép
                // thay thế nó. Người dùng sửa vùng này thì cờ tắt đi và nó thành cấu hình của họ.
                state.setDefaultZoneFlag(slug, true)
            }
            state.markDirty()
            call.respond(DefaultZonesResponse(true, applied.size, applied))
        } catch (e: Exception) {
            call.application.environment.log.error("Error applying default zones", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
        }
    }

    // Áp dụng công tắc tổng — cùng cơ chế bảo vệ như /api/cameras/{id}/ai/apply: restart, chờ
    // Frigate khoẻ lại, không khoẻ thì tự khôi phục config cũ.
    post("/api/settings/detect/apply") {
        try {
            val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
            val result = vn.aiview.nvr.services.FrigateConfigGuard
                .restartAndVerify(File(configPath), "global_detect_apply")
            if (result.ok) vn.aiview.nvr.services.AiConfigState.clearDirty()
            call.respond(ApplyResponse(result.ok, result.rolledBack, result.message))
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

    // Đường kéo luồng của camera: "auto" (mặc định, tự chuyển 554 ↔ 322 khi cổng đang dùng chết),
    // "rtsp" (ép 554 thô), "rtsps" (ép 322 TLS). Xem specs/nvr-rtsps-dual-transport.md.
    patch("/api/cameras/{id}/transport") {
        val id = call.parameters["id"] ?: return@patch call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
        val mode = try {
            Json.parseToJsonElement(call.receiveText()).jsonObject["mode"]?.jsonPrimitive?.content?.trim()?.lowercase()
        } catch (_: Exception) { null }
        if (mode.isNullOrBlank()) {
            return@patch call.respond(HttpStatusCode.BadRequest,
                mapOf("error" to "Thiếu trường mode (auto | rtsp | rtsps)"))
        }
        if (mode !in RtspTransport.VALID_MODES) {
            AppLogger.warn(AppLogger.Module.CAMERA, "RTSP_TRANSPORT", "MODE_REJECTED",
                "slug=$id mode='$mode' — không hợp lệ")
            return@patch call.respond(HttpStatusCode.BadRequest,
                mapOf("error" to "mode phải là auto, rtsp hoặc rtsps"))
        }
        CameraService.setStreamTransport(id, mode).fold(
            onSuccess = { applied ->
                call.respondText(
                    """{"success":true,"mode":"$mode","frigateRestarted":${applied.frigateRestarted}}""",
                    io.ktor.http.ContentType.Application.Json
                )
            },
            onFailure = { e ->
                AppLogger.error(AppLogger.Module.CAMERA, "RTSP_TRANSPORT", "MODE_APPLY_FAIL",
                    "slug=$id mode=$mode err=${e.message}", e)
                if (e is IllegalArgumentException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Camera not found")))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
                }
            }
        )
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


/**
 * Làm sạch toạ độ zone trước khi ghi vào config.yml của Frigate.
 *
 * Frigate 0.16.3 phân biệt toạ độ tương đối (0-1) với pixel tuyệt đối bằng **so sánh CHUỖI**
 * (`frigate/config/camera/zone.py:132`):
 *
 *     explicit = any(p > "1.0" for p in points)
 *
 * `"1.000" > "1.0"` cho **True** — cùng tiền tố nhưng chuỗi dài hơn thì lớn hơn. Một zone chạm
 * mép phải/mép dưới khung hình sinh ra token `1.000`, Frigate liền tưởng là pixel tuyệt đối rồi
 * gọi `int("0.000")` → ValueError → **container crash-loop, mất cả live view lẫn ghi hình** cho
 * tới khi có người sửa tay config.yml. Lỗi này do fuzz test UI phát hiện 2026-07-29.
 *
 * Cách chặn: kẹp mọi giá trị vào [0, 0.999]. Token lớn nhất còn "0.999" nên không bao giờ lớn hơn
 * "1.0" dù so sánh kiểu chuỗi hay kiểu số. Mất 0,1% khung hình — không đáng kể so với việc sập máy.
 * Làm ở backend để bịt cho cả Web UI lẫn đường cloud, thay vì chỉ vá phía frontend.
 */
/**
 * Trả về thông báo lỗi nếu tập tên vùng sẽ làm Frigate từ chối config, null nếu hợp lệ.
 *
 * Kiểm chứng thực tế với validator của Frigate 0.16.3 (2026-08-03):
 *   OK      — "toan_canh", "Toan Canh", "Toàn Cảnh", "Cổng chính", "cong-chinh", "Zone 1"
 *   TỪ CHỐI — vùng trùng tên một camera bất kỳ ("Zones cannot share names with cameras")
 * Nên KHÔNG cần bỏ dấu tiếng Việt; chỉ cần chặn đúng hai thứ Frigate/YAML không chịu được.
 * Tên trùng nhau thì YAML tự nuốt mất một cái (key trùng) — người dùng mất vùng mà không hay,
 * nên chặn luôn ở tầng JSON đầu vào.
 */
internal fun validateZoneNames(names: Collection<String>, configFile: File): String? {
    val cameraNames: Set<String> = try {
        @Suppress("UNCHECKED_CAST")
        val data = Yaml().load<Map<String, Any>>(configFile.readText()) ?: emptyMap()
        (data["cameras"] as? Map<String, Any>)?.keys ?: emptySet()
    } catch (_: Exception) {
        emptySet()
    }

    val seen = mutableSetOf<String>()
    for (name in names) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Tên vùng không được để trống"
        if (trimmed in cameraNames) return "Tên vùng \"$trimmed\" trùng tên một camera — hãy đặt tên khác"
        if (!seen.add(trimmed.lowercase())) return "Có hai vùng cùng tên \"$trimmed\" — mỗi vùng phải một tên riêng"
    }
    return null
}

internal fun sanitizeZoneCoordinates(raw: String): String {
    val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return raw
    val nums = parts.map { it.toDoubleOrNull() ?: return raw }   // định dạng lạ → giữ nguyên, để Frigate tự báo
    return nums.joinToString(",") { v -> String.format(java.util.Locale.US, "%.3f", v.coerceIn(0.0, 0.999)) }
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
