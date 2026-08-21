package vn.aiview.nvr.services

import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Lớp an toàn cho mọi lần ghi `config.yml` rồi restart Frigate.
 *
 * Vì sao cần: config.yml sai một dòng là Frigate crash-loop, và khi Frigate chết thì mất **cả live
 * view lẫn ghi hình** — không chỉ mất tính năng AI. Đã xảy ra thật hai lần:
 *   · 2026-07-29 toạ độ zone `1.000` (Frigate so sánh chuỗi) → xem `sanitizeZoneCoordinates`
 *   · 2026-08-03 input sub-stream còn `roles: []` sau khi tắt AI → "The detect role is required"
 * Cả hai lần đều phải có người vào sửa tay config.yml mới cứu được. Với máy đã giao khách thì
 * đó là mất dữ liệu ghi hình cho tới khi có kỹ thuật viên tới nơi.
 *
 * Cách chặn: chụp lại config trước khi ghi, restart, chờ Frigate báo khoẻ. Không khoẻ trong hạn
 * thì tự trả về bản cũ và restart lại — máy quay về đúng trạng thái đang chạy được, rồi mới báo
 * lỗi cho người dùng.
 */
object FrigateConfigGuard {
    private val log = LoggerFactory.getLogger(FrigateConfigGuard::class.java)

    private const val HEALTH_TIMEOUT_MS = 90_000L
    private const val POLL_INTERVAL_MS = 3_000L

    private val frigateUrl: String get() = System.getenv("FRIGATE_URL") ?: "http://localhost:5000"

    /** Kết quả áp dụng, đủ để frontend hiển thị đúng chuyện gì đã xảy ra. */
    data class ApplyResult(
        val ok: Boolean,
        /** true = đã phải khôi phục bản cũ vì Frigate không lên lại được. */
        val rolledBack: Boolean,
        val message: String,
    )

    private fun backupFileFor(config: File) = File(config.parentFile, "${config.name}.last-good")

    /**
     * Chụp bản hiện tại trước khi ghi đè. Gọi TRONG mutex config, ngay trước khi ghi.
     * Không ném lỗi: không chụp được thì vẫn cho ghi (chặn ghi vì không backup được còn tệ hơn),
     * chỉ ghi log để lần restart sau biết là không có đường lùi.
     */
    fun snapshot(config: File) {
        try {
            if (config.exists()) {
                config.copyTo(backupFileFor(config), overwrite = true)
            }
        } catch (e: Exception) {
            log.warn("CONFIG_SNAPSHOT_FAIL không chụp được config.yml trước khi ghi: ${e.message}")
        }
    }

    /**
     * Restart Frigate rồi chờ nó khoẻ lại. Không khoẻ trong [HEALTH_TIMEOUT_MS] thì khôi phục bản
     * đã chụp và restart lần nữa.
     *
     * Hàm này chặn tới khi có kết luận (tối đa ~3 phút nếu phải rollback) — gọi từ coroutine của
     * route là được, Ktor đã chạy handler trên dispatcher riêng.
     *
     * ⚠️ Gọi NGOÀI `CameraConfigMutex.mutex`: bước rollback tự lấy mutex đó để ghi lại file, giữ
     * sẵn mutex khi gọi vào đây sẽ deadlock.
     */
    suspend fun restartAndVerify(config: File, reason: String): ApplyResult {
        StreamOnlineTracker.noteFrigateRestart(reason)
        triggerRestart()

        if (waitUntilHealthy()) {
            log.info("CONFIG_APPLY_OK Frigate khoẻ lại sau restart ($reason)")
            return ApplyResult(true, false, "Đã áp dụng cấu hình mới.")
        }

        val backup = backupFileFor(config)
        if (!backup.exists()) {
            log.error("CONFIG_APPLY_FAIL Frigate không lên lại và KHÔNG có bản sao lưu để khôi phục")
            return ApplyResult(
                false, false,
                "AI View NVR không khởi động lại được và không có bản sao lưu để khôi phục. " +
                    "Cần kiểm tra thủ công ngay — ghi hình có thể đang dừng."
            )
        }

        log.error("CONFIG_ROLLBACK Frigate không lên lại sau ${HEALTH_TIMEOUT_MS}ms — khôi phục bản trước đó")
        return try {
            CameraConfigMutex.mutex.withLock {
                backup.copyTo(config, overwrite = true)
            }
            StreamOnlineTracker.noteFrigateRestart("rollback")
            triggerRestart()
            val recovered = waitUntilHealthy()
            if (recovered) {
                ApplyResult(
                    false, true,
                    "Cấu hình mới làm AI View NVR không khởi động được nên đã tự khôi phục cấu hình cũ. " +
                        "Hệ thống đang chạy bình thường trở lại, thay đổi vừa rồi KHÔNG được lưu."
                )
            } else {
                ApplyResult(
                    false, true,
                    "Đã khôi phục cấu hình cũ nhưng AI View NVR vẫn chưa lên. Cần kiểm tra thủ công ngay."
                )
            }
        } catch (e: Exception) {
            log.error("CONFIG_ROLLBACK_FAIL $e")
            ApplyResult(false, false, "Không khôi phục được cấu hình cũ: ${e.message}. Cần kiểm tra thủ công ngay.")
        }
    }

    private fun triggerRestart() {
        try {
            val conn = java.net.URL("$frigateUrl/api/restart").openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.responseCode
        } catch (e: Exception) {
            // Bình thường: Frigate đóng kết nối ngay khi bắt đầu restart.
            log.info("Đã gửi lệnh restart Frigate (${e.message})")
        }
    }

    /** Frigate coi là khoẻ khi /api/version trả 200. */
    private suspend fun waitUntilHealthy(): Boolean {
        val deadline = System.currentTimeMillis() + HEALTH_TIMEOUT_MS
        // Chờ một nhịp trước khi hỏi: ngay sau lệnh restart, tiến trình cũ vẫn còn trả 200 và sẽ
        // bị hiểu nhầm là "đã khoẻ" trong khi nó sắp chết.
        kotlinx.coroutines.delay(5_000)
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = java.net.URL("$frigateUrl/api/version").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout = 3_000
                if (conn.responseCode == 200) return true
            } catch (_: Exception) {
                // đang restart
            }
            kotlinx.coroutines.delay(POLL_INTERVAL_MS)
        }
        return false
    }
}
