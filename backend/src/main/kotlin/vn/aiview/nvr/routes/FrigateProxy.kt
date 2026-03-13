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
        requestTimeout = 0 // Disable timeout for long-lived streams
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

                    // For the streams status endpoint, sanitize RTSP credentials before returning
                    // to the browser so that camera passwords are never exposed in network logs.
                    if (path == "streams") {
                        val raw = response.bodyAsText()
                        val sanitized = sanitizeRtspCredentials(raw)
                        call.respondText(sanitized, ContentType.Application.Json, response.status)
                    } else {
                        call.respondBytesWriter(response.contentType(), response.status) {
                            response.bodyAsChannel().copyTo(this)
                        }
                    }
                }
            } catch (e: Exception) {
                call.application.environment.log.error("Error proxying to go2rtc", e)
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Failed to connect to go2rtc"))
            }
        }
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
