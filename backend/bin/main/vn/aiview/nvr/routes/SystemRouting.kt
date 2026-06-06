package vn.aiview.nvr.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.util.concurrent.TimeUnit
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.serialization.Serializable

@Serializable
data class VersionInfo(val version: String, val uptime: Long, val deployVersion: String)

@Serializable
data class HealthInfo(val status: String, val db: String, val version: String, val deployVersion: String, val msg: String? = null)

@Serializable
data class StorageInfo(
    val total: Long,
    val free: Long,
    val diskUsed: Long,
    val folderUsed: Long,
    val cameraUsage: Map<String, Long>
)

@Serializable
data class GeneralResponse(val success: Boolean, val error: String? = null, val filename: String? = null, val size: Long? = null)

val appVersion = System.getenv("APP_VERSION") ?: "1.2.0"
val deployVersion = System.getenv("DEPLOY_VERSION") ?: "dev"
val startTime = System.currentTimeMillis()

fun Route.systemRouting() {
    get("/api/version") {
        val uptimeSec = (System.currentTimeMillis() - startTime) / 1000
        call.respond(VersionInfo(appVersion, uptimeSec, deployVersion))
    }

    get("/api/health") {
        try {
            // Check SQLite is responsive
            transaction {
                val stmt = connection.prepareStatement("SELECT 1", false)
                stmt.executeQuery()
            }
            call.respond(HealthInfo("ok", "healthy", appVersion, deployVersion))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError, HealthInfo("error", "unhealthy", appVersion, deployVersion, e.message))
        }
    }

    get("/api/storage-config") {
        try {
            val mediaDir = File("/app/media")
            val total = mediaDir.totalSpace
            val free = mediaDir.usableSpace
            val diskUsed = total - free
            
            val recordingsDir = File("/app/media/recordings")
            val cameraUsage = mutableMapOf<String, Long>()
            var folderUsed = 0L
            
            if (recordingsDir.exists() && recordingsDir.isDirectory) {
                recordingsDir.listFiles()?.filter { it.isDirectory }?.forEach { camDir ->
                    val size = getFolderSize(camDir)
                    cameraUsage[camDir.name] = size
                    folderUsed += size
                }
            }
            
            call.respond(StorageInfo(total, free, diskUsed, folderUsed, cameraUsage))
        } catch (e: Exception) {
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to "Failed to compute storage", "details" to e.message))
        }
    }

    get("/api/network-config") {
        try {
            var macAddr = "Unknown"
            var ipAddr = "Unknown"
            var netmask = "Unknown"
            
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                
                // We're looking for standard ethernet or wifi interfaces (eth0, wlan0, en0, etc.)
                if (iface.name.startsWith("eth") || iface.name.startsWith("en") || iface.name.startsWith("wlan")) {
                    val macBytes = iface.hardwareAddress
                    if (macBytes != null) {
                        macAddr = macBytes.joinToString(":") { String.format("%02X", it) }
                    }
                    
                    for (addr in iface.interfaceAddresses) {
                        val inetAddress = addr.address
                        if (inetAddress is java.net.Inet4Address && !inetAddress.isLoopbackAddress) {
                            ipAddr = inetAddress.hostAddress
                            // Calculate netmask from prefix length
                            val prefix = addr.networkPrefixLength
                            val mask = -1 shl (32 - prefix)
                            netmask = String.format("%d.%d.%d.%d", (mask ushr 24) and 0xff, (mask ushr 16) and 0xff, (mask ushr 8) and 0xff, mask and 0xff)
                            break
                        }
                    }
                    if (ipAddr != "Unknown") break
                }
            }
            
            // Get DNS by parsing /etc/resolv.conf
            val dnsServers = mutableListOf<String>()
            val resolvFile = File("/etc/resolv.conf")
            if (resolvFile.exists()) {
                resolvFile.readLines().forEach { line ->
                    if (line.trim().startsWith("nameserver")) {
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 2) dnsServers.add(parts[1])
                    }
                }
            }
            if (dnsServers.isEmpty()) dnsServers.add("8.8.8.8")
            
            // Get Gateway by parsing /proc/net/route
            var gateway = "Unknown"
            val routeFile = File("/proc/net/route")
            if (routeFile.exists()) {
                routeFile.readLines().forEach { line ->
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 3 && parts[1] == "00000000") { // destination 0.0.0.0 is default route
                        try {
                            // Gateway is hex encoded, e.g. 0102A8C0 -> 192.168.2.1
                            val hexGw = parts[2]
                            if (hexGw != "00000000") {
                                val a = hexGw.substring(6, 8).toInt(16)
                                val b = hexGw.substring(4, 6).toInt(16)
                                val c = hexGw.substring(2, 4).toInt(16)
                                val d = hexGw.substring(0, 2).toInt(16)
                                gateway = "$a.$b.$c.$d"
                            }
                        } catch (e: Exception) {
                            // Ignored
                        }
                    }
                }
            }
            
            val d1 = if (dnsServers.isNotEmpty()) dnsServers[0] else "8.8.8.8"
            val d2 = if (dnsServers.size > 1) dnsServers[1] else ""
            
            val configObj = mapOf(
                "mac" to macAddr,
                "ip" to ipAddr,
                "subnet" to netmask,
                "gateway" to gateway,
                "dns1" to d1,
                "dns2" to d2
            )
            call.respond(configObj)
        } catch (e: Exception) {
            call.application.environment.log.error("Error fetching network config", e)
            call.respondText("""{"error":"Failed"}""", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.InternalServerError)
        }
    }

    // ------- Export Video API -------

    // POST /api/export-video body: { camera, startTime, endTime }
    // Triggers Frigate's built-in export task
    post("/api/export-video") {
        try {
            val body = call.receive<Map<String, String>>()
            val camera = body["camera"] ?: return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Missing camera"))
            val startTime = body["startTime"] ?: return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Missing startTime"))
            val endTime = body["endTime"] ?: return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Missing endTime"))

            val frigateUrl = System.getenv("FRIGATE_URL") ?: "http://localhost:5000"
            val encodedCamera = java.net.URLEncoder.encode(camera, "UTF-8").replace("+", "%20")
            val targetUrl = "$frigateUrl/api/export/$encodedCamera/start/$startTime/end/$endTime"
            call.application.environment.log.info("Requesting export from: $targetUrl")

            val url = java.net.URL(targetUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            // Send payload (playback config can be realtime or timelapse)
            val payload = """{"playback": "realtime"}"""
            connection.outputStream.use { os ->
                os.write(payload.toByteArray())
            }

            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            val responseCode = connection.responseCode
            val responseExt = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
            }

            if (responseCode !in 200..299) {
                call.application.environment.log.error("Frigate export failed: $responseCode - $responseExt")
                return@post call.respond(io.ktor.http.HttpStatusCode.BadGateway, mapOf("error" to "Frigate Error: $responseExt"))
            }

            // Frigate returns JSON like {"message": "Starting export... ", "export_id": "test_camera_1622548800_1622552400.mp4"}
            call.respondText(responseExt, io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.OK)
        } catch (e: Exception) {
            call.application.environment.log.error("Error exporting video", e)
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown")))
        }
    }

    get("/api/list-exports") {
        val dir = File("/app/media/exports")
        if (!dir.exists()) { call.respond(emptyList<Map<String, Any>>()); return@get }
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }
            ?.sortedByDescending { it.lastModified() }
            ?.map {
                mapOf(
                    "name" to it.name,
                    "size" to String.format(java.util.Locale.US, "%.2f MB", it.length() / (1024.0 * 1024.0)),
                    "time" to java.time.Instant.ofEpochMilli(it.lastModified()).toString(),
                    "path" to "/media/exports/${it.name}"
                )
            }
            ?: emptyList()
        call.respond(files)
    }

    delete("/api/exports/{filename}") {
        val filename = call.parameters["filename"] ?: return@delete call.respond(io.ktor.http.HttpStatusCode.BadRequest)
        val file = File("/app/media/exports/$filename")
        if (file.exists() && file.name.endsWith(".mp4")) {
            file.delete()
            call.respond(GeneralResponse(true))
        } else {
            call.respond(io.ktor.http.HttpStatusCode.NotFound, GeneralResponse(false, "File not found"))
        }
    }

    // ------- Screenshot API -------

    // POST /api/save-screenshot body: { dataUrl, filename? }
    post("/api/save-screenshot") {
        try {
            val body = call.receive<Map<String, String>>()
            val dataUrl = body["dataUrl"] ?: return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Missing dataUrl"))
            val screenshotsDir = File("/app/media/screenshots")
            screenshotsDir.mkdirs()

            // Strip data:image/...;base64, prefix
            val base64Data = if (dataUrl.contains(",")) dataUrl.substringAfter(",") else dataUrl
            val bytes = java.util.Base64.getDecoder().decode(base64Data)

            val filename = body["filename"] ?: "screenshot_${System.currentTimeMillis()}.jpg"
            val outFile = File(screenshotsDir, filename)
            outFile.writeBytes(bytes)

            call.respond(GeneralResponse(true, filename = outFile.name, size = outFile.length()))
        } catch (e: Exception) {
            call.application.environment.log.error("Error saving screenshot", e)
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError, GeneralResponse(false, e.message ?: "Unknown"))
        }
    }

    get("/api/list-screenshots") {
        val dir = File("/app/media/screenshots")
        if (!dir.exists()) { call.respond(emptyList<Map<String, Any>>()); return@get }
        val files = dir.listFiles()?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map {
                mapOf(
                    "name" to it.name,
                    "size" to String.format(java.util.Locale.US, "%.2f MB", it.length() / (1024.0 * 1024.0)),
                    "time" to java.time.Instant.ofEpochMilli(it.lastModified()).toString(),
                    "path" to "/media/screenshots/${it.name}"
                )
            }
            ?: emptyList()
        call.respond(files)
    }

    delete("/api/screenshots/{filename}") {
        val filename = call.parameters["filename"] ?: return@delete call.respond(io.ktor.http.HttpStatusCode.BadRequest)
        val file = File("/app/media/screenshots/$filename")
        if (file.exists()) {
            file.delete()
            call.respond(GeneralResponse(true))
        } else {
            call.respond(io.ktor.http.HttpStatusCode.NotFound, GeneralResponse(false, "File not found"))
        }
    }
}

private fun getFolderSize(folder: File): Long {
    var length = 0L
    folder.listFiles()?.forEach { file ->
        if (file.isFile) {
            length += file.length()
        } else {
            length += getFolderSize(file)
        }
    }
    return length
}
