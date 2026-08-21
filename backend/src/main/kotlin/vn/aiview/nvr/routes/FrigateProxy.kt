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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

/**
 * Lùi transcode cho live view LOCAL khi camera là HEVC (song song với SignalingManager làm cho cloud).
 * KHÔNG còn thử phát HEVC trực tiếp trên trình duyệt: `MediaSource.isTypeSupported('...hvc1/hev1...')`
 * chỉ xác nhận trình duyệt hiểu codec string, không đảm bảo giải mã đúng bitstream thật của camera —
 * test thật với P200-0802N cho isTypeSupported()=true nhưng hình vỡ nửa khung + đơ giữa 2 keyframe.
 * LiveCamera.tsx gọi endpoint này ngay khi dò thấy codec H.265 (`/api/frigate/stream-codec/`), không
 * chờ thử phát trực tiếp thất bại nữa. Đăng ký (idempotent — go2rtc PUT trùng tên chỉ update) stream
 * `${slug}_h264` transcode HEVC→H.264 qua go2rtc ffmpeg, dùng VAAPI khi có `/dev/dri`; trả về tên
 * stream mới để frontend gọi lại `stream.mp4?src=`.
 */
private val hasVaapi: Boolean by lazy { java.io.File("/dev/dri/renderD128").exists() }

/**
 * Dò codec luồng chính TRƯỚC khi mở `stream.mp4` — không được mở rồi bỏ dở như trước (đã gây
 * go2rtc panic thật, xem dưới). Đọc `producers[].medias` của go2rtc qua `/api/streams?src=`, KHÔNG
 * tạo consumer track nào (chỉ đọc SDP đã có sẵn từ RTSP DESCRIBE) nên an toàn tuyệt đối.
 *
 * Lý do bắt buộc phải dò trước: bản vá trước mở `stream.mp4`, đọc Content-Type, rồi NÉM lỗi ngay
 * nếu là HEVC mà KHÔNG đọc/hủy body — go2rtc đã bắt đầu ghi chunk MP4 vào response (push, không chờ
 * client đọc), rồi effect cleanup abort() kết nối giữa chừng. go2rtc 1.9.9 (vendor trong Frigate
 * 0.16.3, không vá được) có race: ghi tiếp vào response sau khi conn đã đóng → panic nil pointer
 * (`pkg/mp4/consumer.go:82` → `WriteBuffer.Write` → `net/http.response.write` trên conn nil) làm
 * CHẾT CẢ TIẾN TRÌNH go2rtc (chung với mọi camera khác) → toàn bộ live view + ghi hình đứng, phải
 * đợi Frigate tự khởi động lại go2rtc. Test thật với camera P200-0802N (H.265) xác nhận đúng
 * `panic: runtime error: invalid memory address or nil pointer dereference` trong log frigate lúc
 * đổi luồng — không được lặp lại lỗi này.
 */
suspend fun probeStreamCodec(slug: String): String? {
    val frigateHost = java.net.URI(frigateUrl).host ?: "localhost"
    return try {
        val resp = proxyClient.get("http://$frigateHost:1984/api/streams?src=${java.net.URLEncoder.encode(slug, "UTF-8")}")
        if (!resp.status.isSuccess()) return null
        val body = resp.bodyAsText()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        val root = json.parseToJsonElement(body).jsonObject
        val medias = root["producers"]?.jsonArray?.firstOrNull()?.jsonObject?.get("medias")?.jsonArray
            ?: return null
        val videoMedia = medias.map { it.jsonPrimitive.content }.firstOrNull { it.startsWith("video", ignoreCase = true) }
            ?: return null
        when {
            Regex("H265|HEVC", RegexOption.IGNORE_CASE).containsMatchIn(videoMedia) -> "h265"
            Regex("H264|AVC", RegexOption.IGNORE_CASE).containsMatchIn(videoMedia) -> "h264"
            else -> "other"
        }
    } catch (e: Exception) {
        null
    }
}

fun Route.frigateProxyRouting() {

    get("/api/frigate/stream-codec/{slug}") {
        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val codec = probeStreamCodec(slug)
        if (codec == null) {
            return@get call.respond(HttpStatusCode.BadGateway, mapOf("error" to "probe_failed"))
        }
        call.respond(mapOf("codec" to codec))
    }

    get("/api/frigate/live-fallback/{slug}") {
        val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val h264Name = "${slug}_h264"
        val src = if (hasVaapi) "ffmpeg:$slug#video=h264#hardware" else "ffmpeg:$slug#video=h264"
        val ok = vn.aiview.nvr.cloud.Go2RtcClient.registerStreamSync(h264Name, src)
        if (!ok) {
            call.application.environment.log.warn("[LIVE_FALLBACK] could not register $h264Name (hw=$hasVaapi)")
            return@get call.respond(HttpStatusCode.BadGateway, mapOf("error" to "transcode_register_failed"))
        }
        call.respond(mapOf("stream" to h264Name))
    }

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
