package vn.aiview.nvr.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.HttpStatusCode

// In a real production Kotlin app, we'd use a dedicated ONVIF library (like be.teletask.onvif:onvif-java-lib)
// or implement the SOAP XML requests manually via Ktor client.
// For this Phase 3 rewrite, we're setting up the structure and stubbing the discovery endpoint 
// which replaces the old `node-onvif` library from the Node.js implementation.

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.UUID

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
                val portStr = deviceObj?.get("port")?.jsonPrimitive?.content
                val port = portStr?.toIntOrNull() ?: 80
                
                val username = credsObj?.get("username")?.jsonPrimitive?.content ?: "admin"
                val password = credsObj?.get("password")?.jsonPrimitive?.content ?: ""
                
                // Fetch real device information and stream URI through SOAP
                val deviceInfo = OnvifSoapClient.getDeviceInfoAndStream(ip, port, username, password)
                
                val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
                val slugRaw = name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
                val slug = "${slugRaw}_${ip.replace(".", "_")}"
                
                var isDuplicate = false
                var shouldUpdate = false
                org.jetbrains.exposed.sql.transactions.transaction {
                    val existingRecord = if (ip.isNotEmpty()) {
                        vn.aiview.nvr.models.Cameras.selectAll().where { vn.aiview.nvr.models.Cameras.ip eq ip }.firstOrNull()
                    } else null
                    
                    if (existingRecord != null) {
                        val configFile = java.io.File(configPath)
                        if (configFile.exists()) {
                            val yaml = org.yaml.snakeyaml.Yaml()
                            val currentConfig = yaml.load<Map<String, Any>>(configFile.readText())
                            val currentCameras = currentConfig["cameras"] as? Map<String, Any> ?: emptyMap()
                            val existingSlug = existingRecord[vn.aiview.nvr.models.Cameras.slug]
                            
                            if (currentCameras.containsKey(existingSlug)) {
                                isDuplicate = true
                            } else {
                                shouldUpdate = true
                            }
                        } else {
                            shouldUpdate = true
                        }
                    }
                    
                    if (!isDuplicate) {
                        if (shouldUpdate && ip.isNotEmpty()) {
                            vn.aiview.nvr.models.Cameras.update({ vn.aiview.nvr.models.Cameras.ip eq ip }) {
                                it[vn.aiview.nvr.models.Cameras.displayName] = name
                                it[vn.aiview.nvr.models.Cameras.slug] = slug
                                it[vn.aiview.nvr.models.Cameras.manufacturer] = deviceInfo.manufacturer
                                it[vn.aiview.nvr.models.Cameras.model] = deviceInfo.model
                            }
                        } else {
                            val existingSlugCount = vn.aiview.nvr.models.Cameras.selectAll().where { vn.aiview.nvr.models.Cameras.slug eq slug }.count()
                            if (existingSlugCount > 0) {
                                isDuplicate = true
                            } else {
                                vn.aiview.nvr.models.Cameras.insert {
                                    it[vn.aiview.nvr.models.Cameras.slug] = slug
                                    it[vn.aiview.nvr.models.Cameras.displayName] = name
                                    it[vn.aiview.nvr.models.Cameras.ip] = ip
                                    it[vn.aiview.nvr.models.Cameras.manufacturer] = deviceInfo.manufacturer
                                    it[vn.aiview.nvr.models.Cameras.model] = deviceInfo.model
                                }
                            }
                        }
                    }
                }
                
                if (isDuplicate) {
                    call.respondText("""{"error":"Camera với Tên hoặc IP này đã tồn tại"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
                
                val file = java.io.File(configPath)
                val yaml = org.yaml.snakeyaml.Yaml()
                val configData = if (file.exists()) yaml.load<MutableMap<String, Any>>(file.readText()) else mutableMapOf()
                
                val cameras = configData.getOrPut("cameras") { mutableMapOf<String, Any>() } as MutableMap<String, Any>
                
                val newCamConfig = mapOf(
                    "ffmpeg" to mapOf(
                        "inputs" to listOf(
                            mapOf(
                                "path" to "rtsp://127.0.0.1:8554/$slug",
                                "input_args" to "preset-rtsp-restream",
                                "roles" to listOf("detect", "record")
                            )
                        )
                    ),
                    "detect" to mapOf("enabled" to false),
                    "record" to mapOf(
                        "enabled" to true,
                        "retain" to mapOf(
                            "days" to 7,
                            "mode" to "all"
                        )
                    )
                )
                
                cameras[slug] = newCamConfig
                
                // Add to go2rtc streams
                val go2rtc = configData.getOrPut("go2rtc") { mutableMapOf<String, Any>() } as MutableMap<String, Any>
                val streams = go2rtc.getOrPut("streams") { mutableMapOf<String, Any>() } as MutableMap<String, Any>
                streams[slug] = listOf("${deviceInfo.rtspUrl}#tcp")
                
                val options = org.yaml.snakeyaml.DumperOptions().apply {
                    defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
                }
                java.io.FileWriter(file).use { writer ->
                    org.yaml.snakeyaml.Yaml(options).dump(configData, writer)
                }

                // Restart Frigate to apply config
                try {
                    val frigateUrl = System.getenv("FRIGATE_URL") ?: "http://localhost:5000"
                    val restartUrl = java.net.URL("$frigateUrl/api/restart")
                    val conn = restartUrl.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.responseCode // trigger the request
                } catch (e: Exception) {
                    call.application.environment.log.warn("Failed to trigger Frigate restart: ${e.message}")
                }
                
                call.respondText("""{"success":true,"message":"Camera added successfully"}""", io.ktor.http.ContentType.Application.Json)
            } catch (e: Exception) {
                call.application.environment.log.error("Error adding ONVIF device", e)
                call.respondText("""{"error":"Tài khoản hoặc mật khẩu sai"}""", io.ktor.http.ContentType.Application.Json, HttpStatusCode.InternalServerError)
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
        
        post("/ptz") {
            // TODO: Implement SOAP PTZ ContinuousMove request
            call.respond(mapOf("status" to "ok", "action" to "ptz_moved"))
        }
        
        post("/test-connection") {
            // TODO: Extract RTSP URL from XML SOAP Response
            call.respond(mapOf("success" to true, "message" to "Connection verified (Simulated)"))
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

            // The frontend expects: { path: "...", streamUri: "...", deviceInfo: { Model: "..." } }
            // Let's parse the path from RTSP URL
            val rtspUrl = deviceInfo.rtspUrl
            val path = rtspUrl.substringAfterLast("/")

            call.respondText("""
                {
                    "path": "/$path",
                    "streamUri": "$rtspUrl",
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
