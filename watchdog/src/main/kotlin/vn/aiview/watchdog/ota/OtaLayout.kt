package vn.aiview.watchdog.ota

import java.io.File

/**
 * Vị trí mọi thứ của OTA trên đĩa, và luật đường dẫn nào được phép ghi đè.
 *
 * `data/ota/` là điểm gặp của ba tiến trình, mỗi bên nhìn nó qua một đường dẫn khác nhau —
 * handoff bằng FILE nên không phải mở thêm cổng mạng nào:
 *   - nvr-wrapper (container)  → /app/data/ota           (mount ./data:/app/data)
 *   - nvr-watchdog (container) → $PROJECT_DIR/data/ota   (mount ${PWD}:${PWD})
 *   - scripts/ota-apply.sh (host) → $PROJECT_DIR/data/ota
 */
class OtaLayout(val projectDir: File) {
    val root       = File(projectDir, "data/ota")
    val incoming   = File(root, "incoming")     // upload đang dở (.part → rename khi xong)
    val staged     = File(root, "staged")       // gói đã verify, đã giải nén
    val rollback   = File(root, "rollback")     // plan.json + images.txt + files/
    val stateFile  = File(root, "state.json")
    val statusFile = File(root, "status.json")
    val requestFile= File(root, "request.json") // wrapper → watchdog
    val triggerFile= File(root, "trigger")      // watchdog → systemd path unit
    val lockFile   = File(root, "lock")

    fun ensureDirs() {
        listOf(root, incoming, staged, rollback, File(rollback, "files")).forEach { it.mkdirs() }
    }

    companion object {
        /** Thư mục khoá công khai tin cậy. Do scripts/install-ota.sh ghi, root:root 0444. */
        val DEFAULT_KEYRING_DIR = File("/opt/nvr/keys/ota-trust.d")

        fun fromEnv(): OtaLayout {
            val dir = System.getenv("PROJECT_DIR")?.takeIf { it.isNotBlank() }
                ?: "/home/toan/toanDoc/Documents/my-nvr"
            return OtaLayout(File(dir))
        }

        /**
         * Tiền tố đường dẫn được phép cho `manifest.files[].path`.
         *
         * Vì sao whitelist chứ không blacklist: gói cập nhật ghi file vào PROJECT_DIR với quyền
         * root. Một manifest độc hại chỉ cần khai `path: "../../../etc/systemd/system/x.service"`
         * hoặc `path: ".env"` là chiếm được máy — và nó ĐÃ QUA được chữ ký nếu khoá ký bị lộ,
         * nên đây là lớp phòng thủ thứ hai cho đúng cái tình huống đó. Blacklist thì luôn thiếu
         * một biến thể nào đó; whitelist thì cái gì không nghĩ tới sẽ bị chặn thay vì cho qua.
         *
         * CỐ TÌNH không có: `.env` (chứa mật khẩu MQTT/Telegram của máy), `secrets/` (token),
         * `media/` + `logs/` (dữ liệu khách), `data/` trừ đúng file dc-bridge.
         */
        private val ALLOWED_OVERLAY_PREFIXES = listOf(
            "docker-compose.yml",
            "docker-compose.offline.yml",
            "VERSION",
            "nginx/",
            "config/",          // config.yml.template, watchdog.yml, .vacuum
            "mosquitto/config/",
            "scripts/",
            "data/dc-bridge",   // đúng một file binary, không phải cả data/
        )

        /**
         * Đường dẫn overlay có hợp lệ không. Kiểm 3 lớp: dạng thô, sau khi chuẩn hoá, và
         * đích thật sau khi resolve — vì `a/../../b` chuẩn hoá xong mới lộ ra là đi ra ngoài.
         */
        fun isAllowedOverlayPath(raw: String, projectDir: File): Boolean {
            val p = raw.trim()
            if (p.isEmpty()) return false
            if (p.startsWith("/")) return false                 // tuyệt đối
            if (p.contains("\\")) return false                  // tách đường dẫn kiểu Windows
            if (p.contains('\u0000')) return false            // NUL byte — chặn kiểu "ok.yml\0/etc/passwd"
            if (p.split('/').any { it == ".." }) return false    // traversal dạng thô

            // Đích thật phải nằm TRONG projectDir. canonicalFile giải cả symlink — chặn kiểu
            // gói trước trồng một symlink rồi gói sau ghi qua nó ra ngoài cây dự án.
            val target = File(projectDir, p).canonicalFile
            val base = projectDir.canonicalFile
            if (!target.path.startsWith(base.path + File.separator)) return false

            val rel = target.path.removePrefix(base.path + File.separator)
            return ALLOWED_OVERLAY_PREFIXES.any { pre ->
                if (pre.endsWith("/")) rel.startsWith(pre) else rel == pre
            }
        }
    }
}
