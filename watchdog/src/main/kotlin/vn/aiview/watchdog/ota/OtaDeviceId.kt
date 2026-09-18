package vn.aiview.watchdog.ota

import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Định danh máy dùng cho check-in — ỔN ĐỊNH qua reboot và DUY NHẤT giữa các máy.
 *
 * NVR không có serial phần cứng như box hay camera, nên phải tự dựng một cái.
 * Hai yêu cầu, và cả hai đều đã có cách vỡ cụ thể:
 *
 *  - **Duy nhất.** `.env.template` ghi thẳng *"blank = nvr-edge-node for every
 *    machine"*, nên dùng `HOSTNAME` trần là CẢ ĐỘI gộp thành MỘT dòng trên
 *    trang theo dõi — mất hẳn khả năng quan sát từng máy, mà bảng vẫn trông
 *    bình thường (một dòng, cập nhật liên tục).
 *  - **Ổn định.** Sinh mới mỗi lần khởi động thì mỗi lần reboot máy xuất hiện
 *    thành một thiết bị MỚI, và bảng phình vô hạn bằng những máy không tồn tại.
 *
 * Vì vậy: ghi xuống đĩa ngay khi sinh, và lần sau LUÔN dùng lại file đó.
 */
object OtaDeviceId {
    private val log = LoggerFactory.getLogger(OtaDeviceId::class.java)

    /** Giá trị mặc định của .env.template — KHÔNG được dùng làm định danh. */
    const val HOSTNAME_MAC_DINH = "nvr-edge-node"

    /**
     * Thứ tự: (1) file đã có ⇒ dùng nguyên; (2) `hostname` người vận hành đặt
     * tay; (3) băm `/etc/machine-id`; (4) ngẫu nhiên.
     *
     * Bước (1) đứng đầu là điều giữ cho định danh ổn định: kể cả khi người ta
     * đổi HOSTNAME về sau, máy vẫn giữ định danh cũ, nên lịch sử check-in của
     * nó không bị đứt làm hai.
     */
    fun resolve(idFile: File, hostname: String, machineIdFile: File): String {
        idFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        val ten = hostname.trim()
        val id = when {
            ten.isNotEmpty() && ten != HOSTNAME_MAC_DINH -> ten
            else -> "NVR-" + tuMachineId(machineIdFile)
        }
        runCatching {
            idFile.parentFile?.mkdirs()
            idFile.writeText(id + "\n")
        }.onFailure {
            // Không ghi được thì vẫn TRẢ VỀ id — mất tính ổn định qua reboot còn
            // hơn là không check-in được lần nào.
            log.warn("DEVICE_ID_KHONG_GHI_DUOC ${idFile.absolutePath}: ${it.message}")
        }
        return id
    }

    private fun tuMachineId(f: File): String {
        val nguon = runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (nguon == null) {
            // Thiếu /etc/machine-id (container, máy lạ) KHÔNG được làm gói OTA
            // sập — rơi về ngẫu nhiên, và file ghi xuống đĩa giữ nó ổn định từ
            // đó về sau.
            log.warn("DEVICE_ID_THIEU_MACHINE_ID ${f.absolutePath} — dùng giá trị ngẫu nhiên, " +
                "ghi xuống đĩa để ổn định về sau")
            val b = ByteArray(6)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }
        val h = MessageDigest.getInstance("SHA-256").digest(nguon.toByteArray())
        // 12 hex = 48 bit. Với vài nghìn máy, xác suất trùng là không đáng kể,
        // và chuỗi vẫn đủ ngắn để đọc bằng mắt trên bảng theo dõi.
        return h.take(6).joinToString("") { "%02x".format(it) }
    }
}
