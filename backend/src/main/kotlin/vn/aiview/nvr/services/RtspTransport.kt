package vn.aiview.nvr.services

import vn.aiview.nvr.utils.AppLogger
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Cho phép NVR kéo camera bằng CẢ `rtsp://` (554) và `rtsps://` (322).
 *
 * Firmware camera Bkav 1.6.9.x đặt RTSP sau một lớp TLS: `rtsps_proxy` nghe 322 rồi chuyển tiếp vào
 * `127.0.0.1:554`. Máy nào cấu hình 554 chỉ nghe loopback thì NVR mất luồng hẳn — Frigate báo
 * `dial tcp <cam>:554: connect: connection refused` và quay vòng restart ffmpeg. Thiết bị hãng khác
 * thì ngược lại: chỉ có 554, không có 322. Nên đường kéo phải chọn được, không được cứng một phía.
 *
 * Nguyên tắc (xem `specs/nvr-rtsps-dual-transport.md`):
 *  - Camera đang kéo tốt thì KHÔNG đổi. Tự ý nâng lên TLS là đổi chính sách sau lưng người vận hành.
 *  - Chỉ chuyển cổng khi cổng đang dùng thực sự chết mà cổng kia sống — tức là để tự chữa, không phải
 *    để tối ưu.
 *  - Vẫn có đường ép tay qua `cameras.stream_transport`.
 *
 * ⚠️ TLS ở đây KHÔNG xác thực chứng chỉ: go2rtc bỏ qua, và cert camera hiện tại là tự ký với
 * `CN=192.168.2.65` (sai SAN so với IP thật) nên có kiểm cũng trượt. Chống nghe lén, không chống
 * giả mạo.
 */
object RtspTransport {

    const val PORT_RTSP = 554
    const val PORT_RTSPS = 322

    const val MODE_AUTO = "auto"
    const val MODE_RTSP = "rtsp"
    const val MODE_RTSPS = "rtsps"

    val VALID_MODES = setOf(MODE_AUTO, MODE_RTSP, MODE_RTSPS)

    private const val PROBE_TIMEOUT_MS = 700

    /** `rtsps://user:pass@10.0.0.5:322/profile1/live` → các mảnh, giữ nguyên phần sau host. */
    data class Parts(
        val secure: Boolean,
        /** đã kèm '@' ở cuối, hoặc rỗng */
        val creds: String,
        val host: String,
        /** null = URL không ghi cổng */
        val port: Int?,
        /** đã kèm '/' đầu, hoặc rỗng; giữ cả query và fragment (#tcp) */
        val path: String,
    ) {
        val effectivePort: Int get() = port ?: if (secure) PORT_RTSPS else PORT_RTSP

        override fun toString(): String {
            val scheme = if (secure) "rtsps://" else "rtsp://"
            val p = if (port != null) ":$port" else ""
            return "$scheme$creds$host$p$path"
        }
    }

    fun parse(url: String): Parts? {
        val trimmed = url.trim()
        val secure = when {
            trimmed.startsWith("rtsps://", ignoreCase = true) -> true
            trimmed.startsWith("rtsp://", ignoreCase = true) -> false
            else -> return null
        }
        val rest = trimmed.substring(if (secure) 8 else 7)
        // Mật khẩu camera Bkav chứa '@' (vd "Bkav@2020") nên phải cắt ở '@' CUỐI CÙNG trước dấu '/'.
        val slashIdx = rest.indexOf('/')
        val authority = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
        val path = if (slashIdx >= 0) rest.substring(slashIdx) else ""
        val atIdx = authority.lastIndexOf('@')
        val creds = if (atIdx >= 0) authority.substring(0, atIdx + 1) else ""
        val hostPort = if (atIdx >= 0) authority.substring(atIdx + 1) else authority
        if (hostPort.isBlank()) return null
        // IPv6 dạng [::1]:554 — cổng là phần sau ']' cuối.
        val colonIdx = if (hostPort.startsWith("[")) {
            hostPort.indexOf(':', startIndex = hostPort.lastIndexOf(']').coerceAtLeast(0))
        } else {
            hostPort.lastIndexOf(':')
        }
        val host: String
        val port: Int?
        if (colonIdx > 0) {
            host = hostPort.substring(0, colonIdx)
            port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: return null
        } else {
            host = hostPort
            port = null
        }
        return Parts(secure, creds, host, port, path)
    }

    /** Đổi sang RTSP thô / RTSPS, kèm cổng mặc định tương ứng. Giữ nguyên creds và path. */
    fun switchTo(url: String, secure: Boolean): String {
        val p = parse(url) ?: return url
        if (p.secure == secure) return url
        return p.copy(secure = secure, port = if (secure) PORT_RTSPS else PORT_RTSP).toString()
    }

    /**
     * Thay cho `url.replace("rtsp://", "rtsp://u:p@")` — cách cũ không đụng được vào URL `rtsps://`
     * (chuỗi "rtsp://" không xuất hiện trong "rtsps://"), nên camera TLS sẽ mất user/pass mà không
     * báo lỗi gì.
     */
    fun injectCredentials(url: String, username: String?, password: String?): String {
        if (username.isNullOrBlank() || password.isNullOrBlank()) return url
        val p = parse(url) ?: return url
        if (p.creds.isNotEmpty()) return url
        return p.copy(creds = "$username:$password@").toString()
    }

    /** Che mật khẩu trước khi đưa vào log. */
    fun redact(url: String): String {
        val p = parse(url) ?: return url
        if (p.creds.isEmpty()) return url
        val user = p.creds.substringBefore(':', p.creds.removeSuffix("@"))
        return p.copy(creds = "$user:***@").toString()
    }

    /** Luồng restream nội bộ của go2rtc — không bao giờ dò, không bao giờ đổi. */
    fun isLoopback(url: String): Boolean {
        val host = parse(url)?.host ?: return false
        return host == "127.0.0.1" || host.startsWith("127.") || host == "localhost" || host == "::1" || host == "[::1]"
    }

    fun probe(host: String, port: Int, timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (_: Exception) {
        false
    }

    /**
     * Chọn URL thật để đưa cho go2rtc.
     *
     * `auto` chỉ chuyển cổng khi cổng đang dùng CHẾT mà cổng kia SỐNG. Cả hai chết thì trả lại URL
     * gốc — cố tình, để log lỗi của Frigate còn chỉ đúng cổng người vận hành đã cấu hình thay vì một
     * cổng do NVR tự đoán.
     *
     * @param slug chỉ dùng cho log
     */
    fun resolve(url: String, mode: String?, slug: String = "?"): String {
        if (url.isBlank()) return url
        val p = parse(url) ?: return url
        if (isLoopback(url)) return url

        when (mode?.lowercase()) {
            MODE_RTSP -> {
                val out = switchTo(url, secure = false)
                AppLogger.info(AppLogger.Module.CAMERA, "RTSP_TRANSPORT", "FORCED",
                    "slug=$slug mode=rtsp url=${redact(out)}")
                return out
            }
            MODE_RTSPS -> {
                val out = switchTo(url, secure = true)
                AppLogger.info(AppLogger.Module.CAMERA, "RTSP_TRANSPORT", "FORCED",
                    "slug=$slug mode=rtsps url=${redact(out)}")
                return out
            }
        }

        val current = p.effectivePort
        if (probe(p.host, current)) {
            AppLogger.info(AppLogger.Module.CAMERA, "RTSP_TRANSPORT", "KEEP",
                "slug=$slug host=${p.host} port=$current secure=${p.secure}")
            return url
        }

        val alt = switchTo(url, secure = !p.secure)
        val altPort = parse(alt)?.effectivePort ?: return url
        if (probe(p.host, altPort)) {
            AppLogger.info(AppLogger.Module.CAMERA, "RTSP_TRANSPORT", "SWITCHED",
                "slug=$slug host=${p.host} from=$current to=$altPort url=${redact(alt)}")
            return alt
        }

        AppLogger.warn(AppLogger.Module.CAMERA, "RTSP_TRANSPORT", "BOTH_DOWN",
            "slug=$slug host=${p.host} tried=$current,$altPort — giu nguyen URL goc, camera se mat hinh")
        return url
    }
}
