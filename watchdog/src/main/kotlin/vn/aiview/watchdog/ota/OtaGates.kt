package vn.aiview.watchdog.ota

import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Chính sách: gói này có ĐƯỢC PHÉP áp lên máy này, lúc này không?
 *
 * Tách hẳn khỏi [OtaVerifier] vì hai bên trả lời hai câu khác nhau, và trộn chúng lại là cách
 * người ta hay tự nhầm rằng "có chữ ký là đủ":
 *
 *   OtaVerifier → "file này đúng là do tôi phát hành, không ai sửa một byte nào"  (xác thực)
 *   OtaGates    → "file này NÊN được cài lên máy này, lúc này"                     (chính sách)
 *
 * sha256 chống HỎNG file. Chữ ký chống SỬA file. Gate ở đây chống ĐÚNG FILE, SAI THỜI ĐIỂM —
 * một lớp lỗi hoàn toàn riêng mà hai thứ trên không chạm tới. Chi tiết từng gate ở dưới.
 *
 * Hàm thuần, không I/O: nhận [Context] rồi trả kết quả. Nhờ vậy test được hết mọi tổ hợp
 * mà không cần dựng container hay chờ tới 2h sáng.
 */
object OtaGates {
    private val log = LoggerFactory.getLogger("OtaGates")

    /** Nguồn kích hoạt — quyết định độ nghiêm của một vài gate. */
    enum class Trigger {
        /** OTA tự động lúc 2h sáng. Nghiêm nhất: không ai đứng cạnh máy để sửa nếu sai. */
        SCHEDULED,

        /** Admin upload file qua UI LAN. Nới đúng một chỗ: cho phép áp lại cùng version. */
        MANUAL_UPLOAD,
    }

    data class Context(
        /** Model của máy này, từ DEVICE_MODEL. */
        val deviceModel: String,
        /** Version watchdog đang chạy — đối chiếu với manifest.minApplier. */
        val applierVersion: String,
        val trigger: Trigger,
        /** Byte trống trên phân vùng chứa PROJECT_DIR. */
        val freeBytes: Long,
        /** Kích thước gói .nvrup (0 nếu chưa biết, ví dụ mới chỉ có manifest remote). */
        val packageBytes: Long = 0,
        val now: Instant = Instant.now(),
    )

    sealed interface Result {
        data object Pass : Result
        /** [code] ổn định để grep log; [reason] viết cho người vận hành đọc, kèm giá trị thật. */
        data class Reject(val code: String, val reason: String) : Result
    }

    /**
     * Chạy toàn bộ gate. Thứ tự có chủ đích: cái rẻ và cái loại được nhiều nhất chạy trước,
     * kiểm đĩa để cuối vì nó là cái duy nhất có thể tự khỏi (dọn đĩa rồi thử lại đêm sau).
     */
    fun evaluate(m: OtaManifest, state: OtaState, ctx: Context): Result {
        val target = m.versionParsed
            ?: return Result.Reject("VERSION_MALFORMED",
                "manifest.version không phải dạng số có dấu chấm: '${m.version}'")

        // ── Gate 0: model ────────────────────────────────────────────────────────────────
        // Chặn cài chéo dòng sản phẩm. Gói của model khác có thể ký hợp lệ hoàn toàn (cùng
        // khoá phát hành) mà vẫn sai phần cứng.
        if (m.model.isBlank() || ctx.deviceModel.isBlank() || m.model != ctx.deviceModel) {
            return Result.Reject("MODEL_MISMATCH",
                "gói dành cho model '${m.model}', máy này là '${ctx.deviceModel}'")
        }

        // ── Gate 3: hạn dùng manifest (chống freeze attack) ──────────────────────────────
        // Kẻ chặn mạng không cần sửa gì: chỉ cần GIỮ NGUYÊN manifest cũ mãi mãi thì máy tưởng
        // "không có bản mới" và không bao giờ nhận bản vá — im lặng tuyệt đối, không log lỗi,
        // không alert, vì theo góc nhìn của máy thì mọi thứ bình thường. Hạn dùng biến một sự
        // cố im lặng thành một tin nhắn Telegram.
        if (m.expiresAt.isNotBlank()) {
            val exp = try {
                Instant.parse(m.expiresAt)
            } catch (e: DateTimeParseException) {
                return Result.Reject("EXPIRY_MALFORMED",
                    "manifest.expiresAt không phải ISO-8601: '${m.expiresAt}'")
            }
            if (ctx.now.isAfter(exp)) {
                val days = java.time.Duration.between(exp, ctx.now).toDays()
                return Result.Reject("MANIFEST_EXPIRED",
                    "manifest hết hạn $days ngày (expiresAt=${m.expiresAt}) — có thể đường mạng đang bị chặn")
            }
        }

        // ── Gate 2: sàn chống hạ cấp (rollback / downgrade attack) ───────────────────────
        // ĐÂY là gate mà chữ ký không thay được. Một gói cũ do CHÍNH BẠN ký vẫn có chữ ký hợp
        // lệ và sha256 khớp — nhưng nếu nó là bản trước khi vá lỗ hổng thì cài lại nó là mở
        // lại lỗ hổng. Kẻ tấn công không cần khoá của bạn, chỉ cần đưa lại đúng file cũ đó
        // (từ CDN cache, mirror chưa sync, hoặc bản họ tải từ tháng trước).
        val floor = state.floorParsed
        if (floor != null && target < floor) {
            return Result.Reject("BELOW_FLOOR",
                "version ${target.raw} thấp hơn sàn chống hạ cấp ${floor.raw} — từ chối hạ cấp " +
                    "(gói cũ dù ký hợp lệ vẫn có thể chứa lỗ hổng đã vá). Hạ sàn phải làm bằng tay tại máy.")
        }

        // ── Không lùi và không đứng yên ──────────────────────────────────────────────────
        val installed = state.installedParsed
        if (installed != null) {
            when {
                target < installed -> return Result.Reject("OLDER_THAN_INSTALLED",
                    "version ${target.raw} cũ hơn bản đang chạy ${installed.raw}")

                target == installed && ctx.trigger == Trigger.SCHEDULED ->
                    // Không phải lỗi — đây là kết cục bình thường của hầu hết các đêm.
                    return Result.Reject("ALREADY_CURRENT",
                        "đang chạy đúng version ${installed.raw}, không có gì để làm")

                target == installed -> log.info(
                    "GATE_SAME_VERSION áp lại ${target.raw} theo yêu cầu admin (upload tay) — cho phép để sửa máy lỗi")
            }
        }

        // ── Gate 1: bậc thang nâng cấp (minFrom) ─────────────────────────────────────────
        // Migration dữ liệu không phải lúc nào cũng nhảy bậc được. Ví dụ thật trong repo này:
        // migrateCloudCamerasIntoCameras() là migration MỘT CHIỀU. Bản nào xoá nó đi (hợp lý,
        // vì cả fleet đã chạy qua) thì máy còn ở version trước đó mà nhảy thẳng lên sẽ mất
        // sạch camera cloud — bảng cloud_cameras còn đầy dữ liệu mà không còn code nào đọc.
        // Và /api/health vẫn trả 200 nên health-check PASS, rollback KHÔNG kích hoạt. Khách
        // mất camera, không ai biết vì sao. Máy cũ nhất trong fleet luôn là máy dễ chết nhất —
        // gate này tồn tại cho đúng nó.
        if (m.minFrom.isNotBlank()) {
            val minFrom = OtaVersion.parseOrNull(m.minFrom)
                ?: return Result.Reject("MINFROM_MALFORMED", "manifest.minFrom không hợp lệ: '${m.minFrom}'")
            if (installed == null) {
                return Result.Reject("MINFROM_UNKNOWN_CURRENT",
                    "gói yêu cầu đang cài ≥ ${minFrom.raw} nhưng máy chưa ghi nhận version đang chạy")
            }
            if (installed < minFrom) {
                return Result.Reject("MINFROM_NOT_MET",
                    "gói ${target.raw} yêu cầu máy đang ở ≥ ${minFrom.raw}, máy này mới ${installed.raw} — " +
                        "phải nâng qua ${minFrom.raw} trước (migration không nhảy bậc được)")
            }
        }

        // ── Gate 4: watchdog đủ mới để hiểu gói ──────────────────────────────────────────
        // Phần đi apply nằm ở watchdog. Gói dùng hook/field mà watchdog cũ không biết sẽ apply
        // THIẾU BƯỚC rồi báo thành công — thất bại im lặng, tệ hơn thất bại ồn ào.
        if (m.minApplier.isNotBlank()) {
            val minApplier = OtaVersion.parseOrNull(m.minApplier)
                ?: return Result.Reject("MINAPPLIER_MALFORMED", "manifest.minApplier không hợp lệ: '${m.minApplier}'")
            val applier = OtaVersion.parseOrNull(ctx.applierVersion)
                ?: return Result.Reject("APPLIER_VERSION_UNKNOWN",
                    "không xác định được version watchdog ('${ctx.applierVersion}')")
            if (applier < minApplier) {
                return Result.Reject("MINAPPLIER_NOT_MET",
                    "gói cần watchdog ≥ ${minApplier.raw}, watchdog này là ${applier.raw}")
            }
        }

        // ── Payload phải nói rõ nó làm gì ───────────────────────────────────────────────
        if (m.images.isEmpty() && m.files.isEmpty()) {
            return Result.Reject("EMPTY_PAYLOAD", "gói không khai image nào và cũng không file nào")
        }
        m.images.forEachIndexed { i, img ->
            when {
                img.tag.isBlank() ->
                    return Result.Reject("IMAGE_NO_TAG", "images[$i] ('${img.name}') không khai tag")

                img.isTarball && (img.path.isBlank() || img.sha256.isBlank()) ->
                    return Result.Reject("IMAGE_TARBALL_INCOMPLETE",
                        "images[$i] ('${img.name}') source=tarball nhưng thiếu path hoặc sha256")

                // Ghim theo DIGEST là bắt buộc, tag thuần thì không. Tag có thể bị trỏ lại sang
                // image khác bất cứ lúc nào mà tên không đổi — dùng tag thuần là bỏ luôn phần
                // toàn vẹn mà đường registry vốn cho miễn phí.
                img.isRegistry && !img.ref.contains("@sha256:") ->
                    return Result.Reject("IMAGE_REF_NOT_PINNED",
                        "images[$i] ('${img.name}') source=registry phải ghim theo digest (@sha256:...), " +
                            "đang là '${img.ref}'")

                !img.isTarball && !img.isRegistry ->
                    return Result.Reject("IMAGE_SOURCE_UNKNOWN",
                        "images[$i] ('${img.name}') source='${img.source}' không hỗ trợ")
            }
        }

        // ── Gói rủi ro cao phải xin phép tường minh ──────────────────────────────────────
        // Nâng Frigate làm config.yml sai một dòng là crash-loop = mất CẢ live view lẫn ghi
        // hình. Mặc định không tự áp, để một cú upload sai không đẩy bản nhảy Frigate ra cả
        // fleet lúc 2h sáng khi không ai xem.
        if (m.risk == "high" && ctx.trigger == Trigger.SCHEDULED && !m.autoApply) {
            return Result.Reject("HIGH_RISK_NEEDS_OPT_IN",
                "gói risk=high (${m.version}) không khai autoApply=true — cần admin áp bằng tay qua UI")
        }

        // ── Đĩa: gate duy nhất có thể tự khỏi ───────────────────────────────────────────
        // Hệ số 3: chứa gói + image đã giải nén + bản cũ giữ lại để rollback. Thiếu chỗ giữa
        // lúc docker load là kịch bản tệ nhất — chết đúng khoảnh khắc image cũ đã bị ghi đè.
        val needed = maxOf(m.requiresFreeBytes, ctx.packageBytes * 3)
        if (needed > 0 && ctx.freeBytes < needed) {
            return Result.Reject("DISK_INSUFFICIENT",
                "cần ${needed / 1_048_576}MB trống, chỉ còn ${ctx.freeBytes / 1_048_576}MB")
        }

        log.info("GATES_PASS ${m.version} (từ ${installed?.raw ?: "?"}) model=${m.model} " +
            "risk=${m.risk} trigger=${ctx.trigger} images=${m.images.size} files=${m.files.size}")
        return Result.Pass
    }
}
