package vn.aiview.watchdog.ota

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/**
 * `status.json` — tiến trình cập nhật, để Web UI poll và để người vận hành đọc khi vào máy.
 *
 * Đây là mặt tiếp xúc giữa watchdog (bên ghi) và backend + host script (bên đọc). Backend
 * TRẢ NGUYÊN VĂN file này ra UI chứ không parse rồi dựng lại — nên watchdog thêm field mới
 * là UI thấy ngay, không phải sửa hai module.
 *
 * File này cũng là thứ duy nhất còn lại khi mọi thứ khác đã chết: nếu apply làm sập cả wrapper
 * lẫn watchdog, host script vẫn ghi được vào đây và người vào máy vẫn đọc được chuyện gì đã
 * xảy ra. Nên nó phải luôn ghi được, luôn parse được, và không bao giờ giữ nửa file.
 */
@Serializable
data class OtaStatus(
    /** Xem [OtaPhase]. */
    val state: String = OtaPhase.IDLE.name,

    /** Version đang được áp (rỗng khi IDLE). */
    val version: String = "",

    /** 0..100, chỉ để vẽ thanh tiến trình — không phải số liệu chính xác. */
    val progress: Int = 0,

    /** Một câu tiếng Việt cho người vận hành đọc. */
    val message: String = "",

    /** requestId của yêu cầu đang xử lý, để UI biết yêu cầu của mình đã được nhận. */
    val requestId: String = "",

    /** manual_upload | scheduled | manual_check */
    val trigger: String = "",

    val updatedAt: String = "",
    val startedAt: String = "",

    /** Mã lý do ổn định khi thất bại (SIG_INVALID, BELOW_FLOOR, MINFROM_NOT_MET…). */
    val errorCode: String = "",

    /**
     * Nhật ký các bước, mới nhất ở cuối. Giữ [LOG_LIMIT] dòng.
     *
     * Vì sao nhét log vào file trạng thái thay vì để người ta đọc log container: lúc apply,
     * container watchdog bị recreate — `docker logs nvr-watchdog` MẤT hết dòng trước đó. Máy
     * field không SSH được ngay, nên nếu không lưu vào file thì phần quan trọng nhất của quá
     * trình cập nhật là phần không xem lại được.
     */
    val log: List<String> = emptyList(),
) {
    companion object {
        const val LOG_LIMIT = 200
    }
}

/**
 * Các pha của một lần cập nhật. Tên vào thẳng `status.json` nên đổi tên là đổi API.
 *
 * ```
 * IDLE → ACQUIRING → VERIFYING → GATING → SNAPSHOT → LOADING → SWAPPING
 *      → HEALTH_CHECK → COMMITTED
 *                     ↘ ROLLING_BACK → ROLLED_BACK | ROLLBACK_FAILED
 *      ↘ REJECTED  (không qua gate — máy không bị chạm gì, đây là kết cục BÌNH THƯỜNG)
 * ```
 */
enum class OtaPhase(val progress: Int, val terminal: Boolean = false) {
    IDLE(0),
    ACQUIRING(10),      // tải gói / đọc file đã upload
    VERIFYING(30),      // chữ ký Ed25519 + sha256 từng payload
    GATING(40),         // 4 gate chính sách
    SNAPSHOT(50),       // ghim image cũ + copy file cũ để có đường lùi
    LOADING(65),        // docker load / docker pull theo digest
    SWAPPING(80),       // host script recreate container
    HEALTH_CHECK(90),
    COMMITTED(100, terminal = true),
    ROLLING_BACK(95),
    ROLLED_BACK(100, terminal = true),
    ROLLBACK_FAILED(100, terminal = true),
    REJECTED(100, terminal = true),
    ;

    /** Đang chạy dở — backend từ chối nhận việc mới khi ở các pha này. */
    val busy: Boolean get() = !terminal && this != IDLE
}

/**
 * Ghi [OtaStatus] an toàn.
 *
 * Mọi lần ghi là temp + rename (atomic trên cùng filesystem). Backend có thể đang đọc đúng
 * lúc mình ghi — nó phải thấy hoặc file cũ hoặc file mới, không bao giờ thấy nửa file JSON
 * (parse lỗi ⇒ UI hiện "không rõ trạng thái" đúng giữa lúc người ta cần biết nhất).
 */
class OtaStatusWriter(private val file: File) {
    private val log = LoggerFactory.getLogger("OtaStatus")
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    private var current = OtaStatus()

    fun load(): OtaStatus {
        current = runCatching { json.decodeFromString(OtaStatus.serializer(), file.readText()) }
            .getOrElse { OtaStatus() }
        return current
    }

    fun snapshot(): OtaStatus = current

    /** Bắt đầu một lần chạy mới: xoá log cũ để không lẫn với lần trước. */
    fun begin(requestId: String, trigger: String, version: String, message: String) {
        val now = Instant.now().toString()
        current = OtaStatus(
            state = OtaPhase.ACQUIRING.name,
            version = version,
            progress = OtaPhase.ACQUIRING.progress,
            message = message,
            requestId = requestId,
            trigger = trigger,
            startedAt = now,
            updatedAt = now,
            log = listOf(line(message)),
        )
        write()
    }

    /** Chuyển pha. [detail] được ghi vào cả `message` và `log`. */
    fun phase(p: OtaPhase, detail: String, errorCode: String = "") {
        current = current.copy(
            state = p.name,
            progress = p.progress,
            message = detail,
            errorCode = errorCode.ifBlank { if (p.terminal && p != OtaPhase.COMMITTED) current.errorCode else "" },
            updatedAt = Instant.now().toString(),
            log = (current.log + line("[${p.name}] $detail")).takeLast(OtaStatus.LOG_LIMIT),
        )
        write()
        val msg = "PHASE ${p.name} ${current.version} — $detail"
        if (p == OtaPhase.ROLLBACK_FAILED) log.error(msg) else log.info(msg)
    }

    /**
     * Gán version khi đã biết.
     *
     * Tách riêng khỏi [begin] vì lúc bắt đầu ta CHƯA được phép tin nội dung gói — version chỉ
     * đọc ra được sau khi chữ ký đã verify. Đây là hệ quả trực tiếp của quy tắc "verify trước,
     * parse sau".
     */
    fun setVersion(v: String) {
        if (v.isBlank() || current.version == v) return
        current = current.copy(version = v, updatedAt = Instant.now().toString())
        write()
    }

    /** Thêm một dòng log mà không đổi pha (tiến độ tải, từng bước nhỏ…). */
    fun note(detail: String) {
        current = current.copy(
            updatedAt = Instant.now().toString(),
            log = (current.log + line(detail)).takeLast(OtaStatus.LOG_LIMIT),
        )
        write()
    }

    /** Về IDLE sau khi đã xong — giữ lại log của lần vừa rồi để còn xem lại. */
    fun idle(message: String = "Không có việc gì đang chạy") {
        current = current.copy(
            state = OtaPhase.IDLE.name,
            progress = 0,
            message = message,
            updatedAt = Instant.now().toString(),
        )
        write()
    }

    private fun line(s: String) = "${Instant.now()} $s"

    private fun write() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(OtaStatus.serializer(), current))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true); tmp.delete()
            }
        } catch (e: Exception) {
            // Không để việc ghi status làm sập cả lần cập nhật — nhưng phải nói to, vì mất
            // status.json nghĩa là UI và người vận hành mất hẳn tầm nhìn vào quá trình.
            log.error("STATUS_WRITE_FAIL không ghi được ${file.absolutePath}: ${e.message}", e)
        }
    }
}

/** `request.json` — backend nhờ watchdog làm việc. */
@Serializable
data class OtaRequest(
    val requestId: String = "",
    /** apply | check */
    val action: String = "",
    /** manual_upload | manual_check */
    val trigger: String = "",
    val requestedAt: String = "",
    /** Tên file trong data/ota/incoming (chỉ với action=apply). */
    val file: String = "",
)
