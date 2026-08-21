package vn.aiview.watchdog.ota

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/**
 * Trạng thái OTA lưu bền trên máy: `data/ota/state.json`.
 *
 * Quan trọng nhất là [floorVersion] — SÀN CHỐNG HẠ CẤP.
 *
 * Vì sao cần, khi đã có sha256 + chữ ký: hai thứ đó chứng minh "file này đúng là của tôi,
 * không ai sửa một byte". Chúng KHÔNG chứng minh "file này là bản mới nhất". Giả sử 1.0.5 có
 * lỗ hổng auth bypass và bạn vá ở 1.0.6 — file gói 1.0.5 do chính bạn ký vẫn còn tồn tại
 * trên CDN cache, trên mirror chưa sync, trong máy kẻ tấn công đã tải từ tháng trước. File đó
 * có chữ ký hợp lệ, sha256 khớp, model khớp, VÀ có lỗ hổng. Kẻ tấn công chỉ cần làm máy tải
 * lại đúng file cũ đó — không sửa gì, không cần khoá của bạn — rồi khai thác lại lỗ hổng đã
 * "vá". Lớp tấn công này có tên: rollback / downgrade attack. Đây là lý do TUF, Android
 * Verified Boot và UEFI Secure Boot đều có anti-rollback counter.
 *
 * Quy tắc: [floorVersion] CHỈ tăng khi apply vào COMMITTED. Rollback KHÔNG tăng sàn — nếu
 * không, một bản lỗi vừa rollback sẽ chặn luôn chính bản cũ đang chạy, và máy tự khoá mình ra
 * khỏi mọi đường cập nhật.
 */
@Serializable
data class OtaState(
    /** Version đang chạy theo ghi nhận của OTA (đối chiếu với /api/version của wrapper). */
    val installedVersion: String = "",

    /** Sàn: từ chối mọi gói có version < giá trị này. Chỉ hạ được bằng tay tại máy. */
    val floorVersion: String = "",

    /** ISO-8601 lần hỏi manifest gần nhất (kể cả khi không có bản mới). */
    val lastCheck: String = "",

    /** ETag của manifest lần trước — đêm không có bản mới thì tốn ~0 byte. */
    val lastEtag: String = "",

    /** Kết cục lần apply gần nhất, để UI và Telegram hiển thị. */
    val lastResult: String = "",
    val lastResultAt: String = "",

    /**
     * Image ID thật của từng tag tại lần commit gần nhất: `tag → sha256:…`
     *
     * Vì sao cần thay vì đọc tag lúc snapshot: tag `nvr-wrapper:latest` có thể bị đổi NGOÀI
     * đường OTA — `docker compose build` gán thẳng vào nó. Lúc đó snapshot đọc tag sẽ ghim
     * chính image MỚI, và rollback thành lệnh không làm gì trong khi vẫn báo "đã lùi thành
     * công". Đã gặp thật khi test 21-08-2026.
     *
     * Ghim theo ID đã ghi ở đây thì đúng cả hai phía: máy field (ID == tag hiện tại, không
     * đổi gì) và máy dev (tag đã bị build ghi đè nhưng ID cũ vẫn trỏ đúng image cũ).
     */
    val committedImages: Map<String, String> = emptyMap(),

    /** Giữ 20 mục gần nhất. Không giữ vô hạn — file này đọc/ghi mỗi đêm. */
    val history: List<OtaHistoryEntry> = emptyList(),
) {
    val installedParsed: OtaVersion? get() = OtaVersion.parseOrNull(installedVersion)
    val floorParsed: OtaVersion? get() = OtaVersion.parseOrNull(floorVersion)

    companion object {
        const val HISTORY_LIMIT = 20
    }
}

@Serializable
data class OtaHistoryEntry(
    val at: String = "",
    val fromVersion: String = "",
    val toVersion: String = "",
    /** COMMITTED | ROLLED_BACK | ROLLBACK_FAILED | REJECTED */
    val outcome: String = "",
    val detail: String = "",
)

/**
 * Đọc/ghi [OtaState] có chống hỏng file.
 *
 * Ghi bằng temp-file + rename (atomic trên cùng filesystem): mất điện giữa lúc ghi thì file cũ
 * còn nguyên chứ không thành file rỗng. Máy này mất điện là chuyện thường — và state.json rỗng
 * nghĩa là mất [OtaState.floorVersion], tức là tự mở lại cửa cho downgrade attack.
 */
class OtaStateStore(private val file: File) {
    private val log = LoggerFactory.getLogger("OtaState")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(): OtaState {
        if (!file.exists()) {
            log.info("STATE_INIT chưa có ${file.absolutePath} — khởi tạo trạng thái trắng")
            return OtaState()
        }
        return try {
            json.decodeFromString(OtaState.serializer(), file.readText())
        } catch (e: Exception) {
            // KHÔNG âm thầm trả về OtaState() ở đây: làm thế là xoá sàn chống hạ cấp mỗi khi
            // file hỏng, biến một lỗi đọc file thành một lỗ bảo mật. Giữ file hỏng lại để điều
            // tra và để người vận hành thấy, rồi vẫn trả trạng thái trắng nhưng có log ERROR.
            val bak = File(file.parentFile, "${file.name}.corrupt.${System.currentTimeMillis()}")
            runCatching { file.copyTo(bak, overwrite = true) }
            log.error("STATE_CORRUPT ${file.name} không parse được (${e.message}) — đã lưu bản lỗi tại ${bak.name}. " +
                "SÀN CHỐNG HẠ CẤP bị mất, cần đặt lại floorVersion bằng tay!")
            OtaState()
        }
    }

    fun save(state: OtaState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(OtaState.serializer(), state))
        // Trên cùng filesystem thì rename là atomic — không có khoảnh khắc nào file chỉ ghi một nửa.
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /** Ghi nhận một lần apply thành công: nâng version đang cài VÀ nâng sàn. */
    fun commit(state: OtaState, toVersion: String, detail: String): OtaState {
        val now = Instant.now().toString()
        // Sàn lấy max(sàn cũ, version mới) — không bao giờ hạ, kể cả khi cài lại bản thấp hơn
        // bằng tay tại máy (đường đó phải hạ sàn tường minh, không đi qua đây).
        val newFloor = listOfNotNull(state.floorParsed, OtaVersion.parseOrNull(toVersion))
            .maxOrNull()?.raw ?: toVersion
        val next = state.copy(
            installedVersion = toVersion,
            floorVersion = newFloor,
            lastResult = "COMMITTED",
            lastResultAt = now,
            history = (state.history + OtaHistoryEntry(now, state.installedVersion, toVersion, "COMMITTED", detail))
                .takeLast(OtaState.HISTORY_LIMIT),
        )
        save(next)
        log.info("STATE_COMMIT installed=$toVersion floor=$newFloor")
        return next
    }

    /** Ghi nhận thất bại. KHÔNG chạm [OtaState.floorVersion] — xem chú thích ở đầu file. */
    fun recordFailure(state: OtaState, toVersion: String, outcome: String, detail: String): OtaState {
        val now = Instant.now().toString()
        val next = state.copy(
            lastResult = outcome,
            lastResultAt = now,
            history = (state.history + OtaHistoryEntry(now, state.installedVersion, toVersion, outcome, detail))
                .takeLast(OtaState.HISTORY_LIMIT),
        )
        save(next)
        log.warn("STATE_FAILURE outcome=$outcome to=$toVersion floor giữ nguyên=${state.floorVersion} — $detail")
        return next
    }
}
