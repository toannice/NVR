package vn.aiview.nvr.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.transactions.transaction

// In a real production Kotlin app, we'd use a dedicated ONVIF library (like be.teletask.onvif:onvif-java-lib)
// or implement the SOAP XML requests manually via Ktor client.
// For this Phase 3 rewrite, we're setting up the structure and stubbing the discovery endpoint 
// which replaces the old `node-onvif` library from the Node.js implementation.

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.UUID
import vn.aiview.nvr.services.CameraService
import vn.aiview.nvr.services.OnvifCameraClient
import vn.aiview.nvr.utils.CryptoUtils

@Serializable
data class OnvifDevice(
    val id: String,
    val name: String,
    val ip: String,
    val status: String,
    val isAdded: Boolean
)

@Serializable
data class OnvifScanResponse(
    val devices: List<OnvifDevice>
)

@Serializable
data class OnvifTestConnectionResponse(
    val success: Boolean,
    val endpoint: String,
    val manufacturer: String,
    val model: String,
    val firmwareVersion: String,
    val serialNumber: String,
    val profileTokens: List<String>
)

@Serializable
data class OnvifPtzResponse(
    val success: Boolean,
    val action: String,
    val profileToken: String
)

@Serializable
data class OnvifErrorResponse(
    val success: Boolean = false,
    val error: String
)

fun Route.onvifRouting() {
    route("/api/onvif") {
        get("/scan") {
            try {
                val devices = discoverOnvifDevices()
                call.respond(mapOf("devices" to devices))
            } catch (e: Exception) {
                call.application.environment.log.error("ONVIF scan failed", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Scan failed: ${e.message}"))
            }
        }
        
        post("/add-device") {
            try {
                val jsonStr = call.receiveText()
                val json = Json.parseToJsonElement(jsonStr).jsonObject
                val deviceObj = json["device"]?.jsonObject
                val credsObj = json["credentials"]?.jsonObject

                val name = deviceObj?.get("name")?.jsonPrimitive?.content ?: "Unknown"
                val ip = deviceObj?.get("ip")?.jsonPrimitive?.content ?: throw Exception("IP Address is required")
                val port = deviceObj?.get("port")?.jsonPrimitive?.content?.toIntOrNull() ?: 80
                val username = credsObj?.get("username")?.jsonPrimitive?.content ?: "admin"
                val password = credsObj?.get("password")?.jsonPrimitive?.content ?: ""

                // Check duplicate before probing
                val slug = "${name.lowercase().replace(Regex("[^a-z0-9]+"), "_")}_${ip.replace(".", "_")}"
                val existsInDb = org.jetbrains.exposed.sql.transactions.transaction {
                    vn.aiview.nvr.models.Cameras.selectAll()
                        .where { vn.aiview.nvr.models.Cameras.slug eq slug }
                        .firstOrNull()
                }
                if (existsInDb != null) {
                    val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
                    val existingSlug = existsInDb[vn.aiview.nvr.models.Cameras.slug]
                    val inYaml = java.io.File(configPath).takeIf { it.exists() }?.let {
                        (org.yaml.snakeyaml.Yaml().load<Map<String, Any>>(it.readText())?.get("cameras") as? Map<*, *>)?.containsKey(existingSlug)
                    } ?: false
                    if (inYaml) {
                        call.respondText("""{"error":"Camera với Tên hoặc IP này đã tồn tại"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                }

                // Next available channel_id
                val channelId = org.jetbrains.exposed.sql.transactions.transaction {
                    val used = vn.aiview.nvr.models.Cameras.selectAll()
                        .mapNotNull { it[vn.aiview.nvr.models.Cameras.channelId]?.toIntOrNull() }.toSet()
                    var next = 1
                    while (next in used) next++
                    next.toString()
                }

                val applied = vn.aiview.nvr.services.CameraService.applyAndPersistFromOnvif(
                    channelId = channelId,
                    host = ip,
                    port = port,
                    user = username,
                    pass = password,
                    displayName = name,
                ).getOrElse {
                    call.application.environment.log.error("CameraService.applyAndPersistFromOnvif failed", it)
                    call.respondText("""{"error":"${it.message ?: "Failed to add camera"}"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    return@post
                }

                call.respondText(
                    """{"success":true,"frigateRestarted":${applied.frigateRestarted},"message":"Camera added successfully"}""",
                    io.ktor.http.ContentType.Application.Json
                )
            } catch (e: Exception) {
                call.application.environment.log.error("Error adding ONVIF device", e)
                call.respondText("""{"error":"${e.message ?: "Tài khoản hoặc mật khẩu sai"}"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }

        get("/discover") {
            try {
                // TODO: Implement actual WS-Discovery UDP broadcast
                call.respond(mapOf(
                    "status" to "scanning",
                    "message" to "ONVIF discovery initiated. (Kotlin implementation pending specific library)"
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Discovery failed"))
            }
        }
        
        // PTZ control through the socat proxy (Edge Gateway → field camera).
        // Body: { "username", "password", "profileToken"?, "action": "move"|"stop", "pan", "tilt", "zoom" }
        // If profileToken is omitted, the first profile reported by the camera is used.
        post("/ptz") {
            try {
                val json = parseJsonBody(call.receiveText())
                val username = json["username"]?.jsonPrimitive?.content ?: "admin"
                val password = json["password"]?.jsonPrimitive?.content ?: ""
                val action = json["action"]?.jsonPrimitive?.content ?: "move"
                val pan  = json["pan"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val tilt = json["tilt"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val zoom = json["zoom"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0

                OnvifCameraClient(username, password).use { cam ->
                    val profileToken = json["profileToken"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?: cam.getProfiles().firstOrNull()
                        ?: throw IllegalStateException("No profileToken provided and none discoverable from camera")

                    if (action.equals("stop", ignoreCase = true)) {
                        cam.stopCamera(profileToken)
                    } else {
                        cam.moveCamera(profileToken, pan, tilt, zoom)
                    }
                    call.respond(OnvifPtzResponse(success = true, action = action, profileToken = profileToken))
                }
            } catch (e: Exception) {
                call.application.environment.log.error("PTZ via proxy failed", e)
                call.respond(HttpStatusCode.BadGateway, OnvifErrorResponse(error = e.message ?: "PTZ failed"))
            }
        }

        // Liveness + auth probe through the socat proxy. Optional body: { "username", "password" }.
        post("/test-connection") {
            try {
                val json = parseJsonBody(call.receiveText())
                val username = json["username"]?.jsonPrimitive?.content ?: "admin"
                val password = json["password"]?.jsonPrimitive?.content ?: ""

                OnvifCameraClient(username, password).use { cam ->
                    val info = cam.getDeviceInformation()
                    val profileTokens = runCatching { cam.getProfiles() }.getOrDefault(emptyList())
                    call.respond(OnvifTestConnectionResponse(
                        success = true,
                        endpoint = OnvifCameraClient.DEFAULT_ENDPOINT,
                        manufacturer = info.manufacturer,
                        model = info.model,
                        firmwareVersion = info.firmwareVersion,
                        serialNumber = info.serialNumber,
                        profileTokens = profileTokens
                    ))
                }
            } catch (e: Exception) {
                call.application.environment.log.warn("ONVIF test-connection via proxy failed: ${e.message}")
                call.respond(HttpStatusCode.BadGateway, OnvifErrorResponse(error = e.message ?: "Connection failed"))
            }
        }
    }

    // ── PTZ control endpoints ──────────────────────────────────────────────────
    // Placed outside /api/onvif so they follow the pattern /api/cameras/{id}/ptz/*

    get("/api/cameras/{id}/ptz/capabilities") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing camera id"))
        try {
            // Manual override wins over the ONVIF probe — for cameras whose GetNodes under-reports
            // pan/tilt, or so the d-pad shows even while the camera/proxy is briefly unreachable.
            val Cam = vn.aiview.nvr.models.Cameras
            val mode = transaction {
                Cam.selectAll().where { Cam.slug eq id }.firstOrNull()?.get(Cam.ptzMode)
            }
            when (mode?.lowercase()) {
                "full" -> return@get call.respond(mapOf("hasPtz" to true, "hasPanTilt" to true, "hasZoom" to true))
                "zoom" -> return@get call.respond(mapOf("hasPtz" to true, "hasPanTilt" to false, "hasZoom" to true))
                "none" -> return@get call.respond(mapOf("hasPtz" to false, "hasPanTilt" to false, "hasZoom" to false))
            }

            val (ip, onvifPort, username, password) = getCameraOnvifConfig(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Camera not found"))

            val capabilities = OnvifSoapClient.checkPtzCapability(ip, onvifPort, username, password)
            call.respond(capabilities)
        } catch (e: Exception) {
            call.application.environment.log.warn("PTZ capability check failed for $id: ${e.message}")
            call.respond(mapOf("hasPtz" to false, "hasPanTilt" to false, "hasZoom" to false))
        }
    }

    post("/api/cameras/{id}/ptz/move") {
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing camera id"))
        try {
            val (ip, onvifPort, username, password) = getCameraOnvifConfig(id)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Camera not found"))

            val jsonStr = call.receiveText()
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            var pan   = json["pan"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            var tilt  = json["tilt"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            val zoom  = json["zoom"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0

            // Enforce the camera's PTZ classification SERVER-SIDE so a stray pan/tilt never reaches a
            // zoom-only (or no-PTZ) camera and triggers a SOAP fault ("speed out of bounds") + log spam.
            // The UI already hides the unsupported controls; this is the authoritative backstop.
            val Cam = vn.aiview.nvr.models.Cameras
            val (ptzEnabled, ptzMode) = transaction {
                Cam.selectAll().where { Cam.slug eq id }.firstOrNull()
                    ?.let { it[Cam.ptzEnabled] to it[Cam.ptzMode]?.lowercase() } ?: (false to null)
            }
            if (!ptzEnabled || ptzMode == "none") {
                return@post call.respond(HttpStatusCode.NoContent)   // PTZ off → silently ignore
            }
            if (ptzMode == "zoom") { pan = 0.0; tilt = 0.0 }          // zoom-only → drop pan/tilt
            if (pan == 0.0 && tilt == 0.0 && zoom == 0.0) {
                return@post call.respond(HttpStatusCode.NoContent)   // nothing left to send → no fault
            }

            val (ptzUrl, profileToken) = OnvifSoapClient.getPtzServiceUrlAndTokenCached(ip, onvifPort, username, password)
            OnvifSoapClient.continuousMove(ptzUrl, profileToken, username, password, pan, tilt, zoom)
            call.respond(mapOf("success" to true))
        } catch (e: Exception) {
            call.application.environment.log.error("PTZ move failed for $id", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "PTZ move failed")))
        }
    }

    post("/api/cameras/{id}/ptz/stop") {
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing camera id"))
        try {
            val (ip, onvifPort, username, password) = getCameraOnvifConfig(id)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Camera not found"))

            val (ptzUrl, profileToken) = OnvifSoapClient.getPtzServiceUrlAndTokenCached(ip, onvifPort, username, password)
            OnvifSoapClient.stopMove(ptzUrl, profileToken, username, password)
            call.respond(mapOf("success" to true))
        } catch (e: Exception) {
            call.application.environment.log.error("PTZ stop failed for $id", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "PTZ stop failed")))
        }
    }

    post("/api/probe-onvif") {
        try {
            val jsonStr = call.receiveText()
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            val ip = json["ip"]?.jsonPrimitive?.content ?: throw Exception("IP Address required")
            val portStr = json["port"]?.jsonPrimitive?.content
            val port = portStr?.toIntOrNull() ?: 80
            val username = json["username"]?.jsonPrimitive?.content ?: "admin"
            val password = json["password"]?.jsonPrimitive?.content ?: ""

            // Use the SoapClient from earlier!
            val deviceInfo = OnvifSoapClient.getDeviceInfoAndStream(ip, port, username, password)

            val rtspUrl = deviceInfo.rtspUrl
            // Full path after host:port (strip scheme + optional user:pass@), e.g.
            // rtsp://admin:pass@192.168.2.64:554/profile1/live -> profile1/live.
            // (Was substringAfterLast("/") which dropped "profile1/" and yielded a broken "/live".)
            val authorityAndPath = rtspUrl.substringAfter("://").substringAfterLast('@')
            val path = if (authorityAndPath.contains('/')) authorityAndPath.substringAfter('/') else ""
            val subUriJson = if (deviceInfo.subRtspUrl != null) "\"${deviceInfo.subRtspUrl}\"" else "null"

            call.respondText("""
                {
                    "path": "/$path",
                    "streamUri": "$rtspUrl",
                    "subStreamUri": $subUriJson,
                    "deviceInfo": {
                        "Model": "${deviceInfo.model}"
                    }
                }
            """.trimIndent(), io.ktor.http.ContentType.Application.Json)
        } catch (e: Exception) {
            call.application.environment.log.error("Error probing ONVIF camera", e)
            call.respondText("""{"error":"${e.message}"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
        }
    }
}

/** Parses a request body into a JsonObject, tolerating an empty/blank body. */
private fun parseJsonBody(body: String): JsonObject =
    if (body.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body).jsonObject

/**
 * Reads camera config (IP, ONVIF port, username, password) for a given camera slug.
 * Returns null if camera not found.
 * Returns a data class (ip, onvifPort, username, password).
 */
private data class CameraOnvifConfig(val ip: String, val onvifPort: Int, val username: String, val password: String?)

private fun getCameraOnvifConfig(slug: String): CameraOnvifConfig? {
    // DB row holds ip, onvif_port, and (for proxy/cloud cameras) the ONVIF username + encrypted password.
    val Cam = vn.aiview.nvr.models.Cameras
    val dbRow = transaction {
        Cam.selectAll().where { Cam.slug eq slug }.firstOrNull()
    } ?: return null
    val onvifPort = dbRow[Cam.onvifPort]
    val dbIp = dbRow[Cam.ip]?.takeIf { it.isNotBlank() }
    val dbUser = dbRow[Cam.username]?.takeIf { it.isNotBlank() }
    val dbPass = dbRow[Cam.passwordEncrypted]?.takeIf { it.isNotBlank() }
        ?.let { runCatching { CryptoUtils.decrypt(it) }.getOrNull() }

    // Prefer credentials embedded in the go2rtc RTSP URL (LAN cameras keep working unchanged);
    // fall back to the DB record for proxy/cloud cameras whose restream URL carries no user:pass
    // (e.g. rtsp://10.34.24.12:8554/camera1M).
    var urlIp: String? = null
    var urlUser: String? = null
    var urlPass: String? = null
    runCatching {
        val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
        val configFile = java.io.File(configPath)
        if (configFile.exists()) {
            val configData = org.yaml.snakeyaml.Yaml().load<Map<String, Any>>(configFile.readText())
            val go2rtc = configData?.get("go2rtc") as? Map<*, *>
            val streams = go2rtc?.get("streams") as? Map<*, *>
            val streamUrl = (streams?.get(slug) as? List<*>)?.firstOrNull()?.toString()
            if (streamUrl != null) {
                val rtspUrl = streamUrl.substringBeforeLast("#") // strip #tcp/#udp (no-op if absent)
                val afterScheme = rtspUrl.removePrefix("rtsp://")
                // host parses correctly whether or not credentials are present
                urlIp = afterScheme.substringAfterLast('@').substringBefore('/').substringBefore(':')
                val atIdx = afterScheme.lastIndexOf('@')
                if (atIdx >= 0) {
                    val creds = afterScheme.substring(0, atIdx)
                    val colon = creds.indexOf(':')
                    urlUser = if (colon >= 0) creds.substring(0, colon) else creds
                    urlPass = if (colon >= 0) creds.substring(colon + 1) else null
                }
            }
        }
    }

    val ip = (urlIp?.takeIf { it.isNotBlank() }) ?: dbIp ?: return null
    // Use the URL credential pair when the URL carried a username; otherwise the DB pair.
    val username = urlUser ?: dbUser ?: return null
    val password = if (urlUser != null) urlPass else dbPass
    return CameraOnvifConfig(ip, onvifPort, username, password)
}

private suspend fun discoverOnvifDevices(): List<OnvifDevice> = withContext(Dispatchers.IO) {

    val devices = mutableListOf<OnvifDevice>()
    val messageId = "uuid:${UUID.randomUUID()}"
    val probeMsg = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing">
            <s:Header>
                <a:Action s:mustUnderstand="1">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>
                <a:MessageID>$messageId</a:MessageID>
                <a:ReplyTo>
                    <a:Address>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address>
                </a:ReplyTo>
                <a:To s:mustUnderstand="1">urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>
            </s:Header>
            <s:Body>
                <Probe xmlns="http://schemas.xmlsoap.org/ws/2005/04/discovery">
                    <d:Types xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dp0="http://www.onvif.org/ver10/network/wsdl">dp0:NetworkVideoTransmitter</d:Types>
                </Probe>
            </s:Body>
        </s:Envelope>
    """.trimIndent().toByteArray()

    try {
        DatagramSocket().use { socket ->
            socket.soTimeout = 3000
            socket.broadcast = true
            
            val address = InetAddress.getByName("239.255.255.250")
            val packet = DatagramPacket(probeMsg, probeMsg.size, address, 3702)
            socket.send(packet)
            
            val receiveData = ByteArray(4096)
            val startTime = System.currentTimeMillis()
            
            while (System.currentTimeMillis() - startTime < 3000) {
                try {
                    val receivePacket = DatagramPacket(receiveData, receiveData.size)
                    socket.receive(receivePacket)
                    
                    val response = String(receivePacket.data, 0, receivePacket.length)
                    val ip = receivePacket.address.hostAddress
                    
                    // Simple regex to extract name or XAddrs
                    val nameMatch = Regex("<[^:]+:Scopes>(.*?)</[^:]+:Scopes>").find(response)
                    var name = "Unknown ONVIF Device"
                    if (nameMatch != null) {
                        val scopes = nameMatch.groupValues[1]
                        val nameScope = scopes.split(" ").find { it.contains("onvif://www.onvif.org/name/") }
                        if (nameScope != null) {
                            name = nameScope.substringAfterLast("/")
                            name = java.net.URLDecoder.decode(name, "UTF-8")
                        }
                    }
                    
                    if (devices.none { it.ip == ip }) {
                        devices.add(OnvifDevice(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            ip = ip,
                            status = "online",
                            isAdded = false
                        ))
                    }
                } catch (e: SocketTimeoutException) {
                    break
                }
            }
        }
    } catch (e: Exception) {
        println("Discovery error: ${e.message}")
    }
    
    return@withContext devices
}
