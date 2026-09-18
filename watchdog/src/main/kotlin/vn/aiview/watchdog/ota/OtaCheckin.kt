package vn.aiview.watchdog.ota

import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * Kênh BÁO CÁO thứ hai, bên cạnh Telegram.
 *
 * Hai kênh phục vụ hai người khác nhau và không thay được nhau: Telegram báo
 * NGAY cho người đang trực; check-in dựng BỨC TRANH CẢ ĐỘI cho người vận hành
 * (bao nhiêu máy đã lên bản mới, máy nào lỗi, máy nào mất liên lạc). Bỏ một
 * cái là mất một góc nhìn.
 *
 * ── BA BẤT BIẾN ─────────────────────────────────────────────────────────────
 *
 * 1. **Check-in hỏng KHÔNG được cản cập nhật.** Mọi lỗi mạng/HTTP đều ghi nhật
 *    ký rồi trả `false`; không ném ra ngoài, không dừng vòng OTA. Đây là kênh
 *    QUAN SÁT, không phải kênh ĐIỀU KHIỂN — hợp đồng §B.4 nói thẳng điều đó.
 *    Một máy chủ theo dõi chết không được phép làm cả đội ngừng nhận bản vá.
 *
 * 2. **Kết cục lạ ⇒ `install_failed`, KHÔNG `ok`.** Trang theo dõi lọc "có
 *    lỗi" bằng `last_result != ok && != no_update`. Một outcome đời sau chưa
 *    biết mà ánh xạ thành `ok` sẽ biến một thất bại thành thành công trên bảng
 *    — máy đang hỏng mà không xuất hiện trong danh sách cần xem.
 *
 * 3. **Token lấy một lần rồi lưu.** Thiết bị không tự tính được
 *    `X-Device-Token`; nó xin qua `POST /api/ota/dang-ky` bằng enroll key nhúng
 *    theo dòng máy, rồi lưu lại. Gọi lại chỉ khi mất token.
 */
class OtaCheckin(
    private val baseUrl: String,
    private val model: String,
    private val enrollKey: String,
    private val tokenFile: File,
    private val deviceId: String,
) {
    private val log = LoggerFactory.getLogger(OtaCheckin::class.java)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    companion object {
        /** Giới hạn của hợp đồng §B.2 cho `lastError`. */
        const val MAX_LOI = 256

        /**
         * Ánh xạ kết cục của watchdog sang từ vựng `lastResult` của hợp đồng §B.3.
         *
         * Giá trị KHÔNG BIẾT ⇒ `install_failed`. Xem bất biến 2 ở chú thích lớp:
         * rơi về `ok` là biến một thất bại thành thành công trên bảng theo dõi.
         */
        val anhXaKetCuc: (String) -> String = { outcome ->
            when (outcome) {
                "COMMITTED" -> "ok"
                "" -> "no_update"
                "ROLLED_BACK", "ROLLBACK_FAILED" -> "rolled_back"
                "REJECTED" -> "install_failed"
                "SIG_INVALID" -> "sig_invalid"
                "FETCH_FAILED" -> "fetch_failed"
                "NO_SPACE" -> "no_space"
                else -> "install_failed"
            }
        }

        /** Cắt `lastError` về đúng giới hạn hợp đồng. */
        fun catLoi(s: String): String = if (s.length <= MAX_LOI) s else s.take(MAX_LOI)
    }

    /**
     * Gửi một báo cáo. Trả `true` nếu máy chủ nhận.
     *
     * KHÔNG ném ngoại lệ trong mọi trường hợp — xem bất biến 1.
     */
    fun bao(state: OtaState, fw: String, freeMB: Long, uptimeSec: Long): Boolean = runCatching {
        val token = layToken() ?: return false
        val ketCuc = state.history.firstOrNull()
        val than = buildString {
            append("""{"schema":1""")
            append(""","serial":"${escape(deviceId)}"""")
            append(""","model":"${escape(model)}"""")
            append(""","fw":"${escape(fw)}"""")
            append(""","updaterVersion":"${escape(phienBanWatchdog())}"""")
            append(""","components":{"floor":"${escape(state.floorVersion)}"}""")
            append(""","lastResult":"${escape(anhXaKetCuc(state.lastResult))}"""")
            append(""","lastError":"${escape(catLoi(ketCuc?.detail ?: ""))}"""")
            append(""","lastReleaseId":"${escape(ketCuc?.toVersion ?: "")}"""")
            append(""","releaseComplete":${state.lastSeenSequence <= state.appliedSequence}""")
            append(""","lastSeenSequence":${state.lastSeenSequence}""")
            append(""","appliedSequence":${state.appliedSequence}""")
            append(""","freeSpaceMB":$freeMB""")
            append(""","uptimeSec":$uptimeSec""")
            append(""","ts":"${Instant.now()}"""")
            append("}")
        }
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/api/ota/checkin"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("X-Device-Token", token)
            .POST(HttpRequest.BodyPublishers.ofString(than))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        when (resp.statusCode()) {
            200 -> true
            401 -> {
                // Token hết hiệu lực (máy chủ đổi TOKEN_SECRET). Bỏ token đã lưu
                // để chu kỳ sau xin lại — nếu giữ, máy im lặng vĩnh viễn.
                log.warn("CHECKIN_401 token bị từ chối — xoá token đã lưu để xin lại chu kỳ sau")
                tokenFile.delete()
                false
            }
            else -> {
                log.warn("CHECKIN_HTTP_${resp.statusCode()} ${resp.body().take(200)}")
                false
            }
        }
    }.getOrElse {
        // Bất biến 1: KHÔNG ném ra ngoài. Một máy chủ theo dõi chết không được
        // phép làm cả đội ngừng nhận bản vá.
        log.warn("CHECKIN_LOI ${it.javaClass.simpleName}: ${it.message}")
        false
    }

    /** Đọc token đã lưu, hoặc xin mới qua /api/ota/dang-ky. */
    private fun layToken(): String? {
        tokenFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.length == 64 }?.let { return it }
        if (enrollKey.isBlank()) {
            log.warn("CHECKIN_KHONG_CO_ENROLL_KEY — không xin được token; đặt ota.enroll_key trong watchdog.yml")
            return null
        }
        val than = """{"schema":1,"serial":"${escape(deviceId)}","model":"${escape(model)}"}"""
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/api/ota/dang-ky"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("X-Enroll-Key", enrollKey)
            .POST(HttpRequest.BodyPublishers.ofString(than))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) {
            log.warn("DANGKY_HTTP_${resp.statusCode()} ${resp.body().take(200)}")
            return null
        }
        val tok = Regex("\"token\"\\s*:\\s*\"([0-9a-f]{64})\"").find(resp.body())?.groupValues?.get(1)
        if (tok == null) {
            log.warn("DANGKY_THAN_LA không tìm được token trong thân: ${resp.body().take(120)}")
            return null
        }
        runCatching {
            tokenFile.parentFile?.mkdirs()
            tokenFile.writeText(tok)
            tokenFile.setReadable(false, false)
            tokenFile.setReadable(true, true)
            tokenFile.setWritable(false, false)
            tokenFile.setWritable(true, true)
        }.onFailure { log.warn("TOKEN_KHONG_LUU_DUOC ${tokenFile.absolutePath}: ${it.message}") }
        return tok
    }

    private fun phienBanWatchdog(): String =
        runCatching { javaClass.`package`?.implementationVersion }.getOrNull() ?: "0"

    /** Thoát chuỗi cho JSON dựng bằng tay — thiết bị không có thư viện nào khác. */
    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
}
