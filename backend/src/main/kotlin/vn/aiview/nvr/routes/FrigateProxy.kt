package vn.aiview.nvr.routes

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.utils.io.copyTo

val proxyClient = HttpClient(CIO) {
    expectSuccess = false
    engine {
        requestTimeout = 0  // 0 = disabled (CIO documented sentinel); long-lived streams never time out
        endpoint {
            connectTimeout = 10_000  // fail fast if go2rtc is down
            // CRITICAL: must be Long.MAX_VALUE, NOT 0, for "no read timeout".
            // Ktor 3.0.1's io.ktor.network.util.Timeout treats ONLY Long.MAX_VALUE as
            // "infinite"; a value of 0 makes the timeout coroutine busy-spin (runnable)
            // forever. With one such job per long-lived live stream, the 64-thread
            // DefaultDispatcher pool saturates and ALL body-writing responses (static
            // files + stream proxy) hang -> black live view + nginx 502.
            socketTimeout = Long.MAX_VALUE
        }
    }
}

val frigateUrl = System.getenv("FRIGATE_URL") ?: "http://localhost:5000"

// Masks RTSP credentials (user:pass) in URLs to prevent leaking secrets to the browser.
// Handles passwords that contain '@' by greedily matching up to the last '@' before the host.
// e.g. rtsp://admin:Bkav@2020@192.168.1.1:554/path -> rtsp://***:***@192.168.1.1:554
private fun sanitizeRtspCredentials(raw: String): String {
    // Use greedy .+ to consume everything including embedded '@' in passwords,
    // then the last '@' followed by host (no @, /, space, quote) and optional port + path.
    return raw.replace(
        Regex("rtsp://.+@([^/:@\\s\"]+)(:\\d+)?[^\\s\"]*")
    ) { match ->
        val host = match.groupValues[1]
        val port = match.groupValues[2]
        "rtsp://***:***@$host$port"
    }
}

fun Route.frigateProxyRouting() {

    // ── go2rtc pass-through with credential sanitization for streams ──────────
    route("/api/frigate/go2rtc/{path...}") {
        handle {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val queryParams = call.request.queryString().let { if (it.isNotEmpty()) "?$it" else "" }

            // Determine go2rtc URL based on frigateUrl host
            val frigateHost = java.net.URI(frigateUrl).host ?: "localhost"
            val targetUrl = "http://$frigateHost:1984/api/$path$queryParams"

            try {
                proxyClient.prepareRequest(targetUrl) {
                    method = call.request.httpMethod
                    headers {
                        call.request.headers.forEach { key, values ->
                            if (!key.equals(HttpHeaders.Host, ignoreCase = true) &&
                                !key.equals(HttpHeaders.ContentLength, ignoreCase = true) &&
                                !key.equals(HttpHeaders.ContentType, ignoreCase = true)) {
                                values.forEach { value -> append(key, value) }
                            }
                        }
                    }
                    val bodyBytes = call.receiveChannel()
                    setBody(bodyBytes)
                    if (call.request.contentType() != null) {
                        contentType(call.request.contentType()!!)
                    }
                }.execute { response ->
                    response.headers.forEach { key, values ->
                        if (!key.equals(HttpHeaders.ContentType, ignoreCase = true) &&
                            !key.equals(HttpHeaders.ContentLength, ignoreCase = true) &&
                            !key.equals(HttpHeaders.TransferEncoding, ignoreCase = true)) {
                            values.forEach { call.response.header(key, it) }
                        }
                    }

                    if (path == "streams") {
                        val raw = response.bodyAsText()
                        val sanitized = sanitizeRtspCredentials(raw)
                        call.respondText(sanitized, ContentType.Application.Json, response.status)
                    } else if (response.status.isSuccess()) {
                        call.respondBytesWriter(response.contentType(), response.status) {
                            response.bodyAsChannel().copyTo(this)
                        }
                    } else {
                        // Fast-fail: don't stream error bodies; one bad camera can't hold pool connections
                        val src = call.request.queryParameters["src"] ?: path
                        call.application.environment.log.warn("go2rtc error ${response.status.value} src=$src")
                        call.respond(response.status)
                    }
                }
            } catch (e: Exception) {
                val src = call.request.queryParameters["src"] ?: path
                call.application.environment.log.error("Error proxying to go2rtc src=$src: ${e.message}")
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Failed to connect to go2rtc"))
            }
        }
    }

    get("/api/frigate/api/recordings/summary") {
        val camera = call.request.queryParameters["camera"] ?: call.request.queryParameters["cameras"]
        val recordingsDir = java.io.File("/app/media/recordings")
        val summaryMap = mutableMapOf<String, Boolean>()
        
        if (recordingsDir.exists() && recordingsDir.isDirectory) {
            val zoneId = java.time.ZoneId.systemDefault()
            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH")
            
            recordingsDir.listFiles()?.filter { it.isDirectory }?.forEach { dateDir ->
                val dateStr = dateDir.name // e.g. "2026-03-25"
                if (dateStr.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                    dateDir.listFiles()?.filter { it.isDirectory }?.forEach { hourDir ->
                        val hourStr = hourDir.name // e.g. "00", "07"
                        
                        var hasVideo = false
                        if (!camera.isNullOrBlank()) {
                            val camList = camera.split(",")
                            for (cam in camList) {
                                val camDir = java.io.File(hourDir, cam)
                                if (camDir.exists() && camDir.isDirectory && (camDir.list()?.isNotEmpty() == true)) {
                                    hasVideo = true
                                    break
                                }
                            }
                        } else {
                            val cams = hourDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
                            hasVideo = cams.any { it.list()?.isNotEmpty() == true }
                        }
                        
                        if (hasVideo) {
                            try {
                                val utcStr = "$dateStr $hourStr"
                                val utcDate = java.time.LocalDateTime.parse(utcStr, formatter)
                                val zonedDateTimeUtc = utcDate.atZone(java.time.ZoneId.of("UTC"))
                                val localDateTime = zonedDateTimeUtc.withZoneSameInstant(zoneId).toLocalDate()
                                
                                val localDateStr = localDateTime.toString()
                                summaryMap[localDateStr] = true
                            } catch (e: Exception) {
                                call.application.environment.log.warn("Error parsing date: \$dateStr \$hourStr", e)
                            }
                        }
                    }
                }
            }
        }
        call.respond(summaryMap)
    }

    route("/api/frigate/{path...}") {
        handle {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
            val queryParams = call.request.queryString().let { if (it.isNotEmpty()) "?$it" else "" }
            val targetUrl = "$frigateUrl/$path$queryParams"
            
            try {
                proxyClient.prepareRequest(targetUrl) {
                    method = call.request.httpMethod
                    headers {
                        call.request.headers.forEach { key, values ->
                            if (!key.equals(HttpHeaders.Host, ignoreCase = true) &&
                                !key.equals(HttpHeaders.ContentLength, ignoreCase = true) &&
                                !key.equals(HttpHeaders.ContentType, ignoreCase = true)) {
                                values.forEach { value -> append(key, value) }
                            }
                        }
                    }
                    val bodyBytes = call.receiveChannel()
                    setBody(bodyBytes)
                    if (call.request.contentType() != null) {
                        contentType(call.request.contentType()!!)
                    }
                }.execute { response ->
                    response.headers.forEach { key, values ->
                        if (!key.equals(HttpHeaders.ContentType, ignoreCase = true) &&
                            !key.equals(HttpHeaders.ContentLength, ignoreCase = true) &&
                            !key.equals(HttpHeaders.TransferEncoding, ignoreCase = true)) {
                            values.forEach { call.response.header(key, it) }
                        }
                    }

                    call.respondBytesWriter(response.contentType(), response.status) {
                        response.bodyAsChannel().copyTo(this)
                    }
                }
            } catch (e: Exception) {
                call.application.environment.log.error("Proxy error: \${e.message}", e)
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Proxy to Frigate failed", "details" to (e.message ?: "Unknown")))
            }
        }
    }
}
