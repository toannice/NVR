package vn.aiview.nvr.services

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import vn.aiview.nvr.utils.AppLogger

/**
 * Ô cấu hình chỉnh lại khoanh vùng AI khi lệch — người dùng đã chốt "tự dò + tự bảo vệ" (dò kích
 * thước luồng chính bằng ffprobe, hộp vượt khung thì bỏ vẽ) nhưng vẫn cần đường chỉnh tay cho
 * trường hợp camera báo toạ độ hơi lệch khung (ống kính/crop khác góc ffprobe thấy).
 *
 * Lưu qua [AppSettingsService] dưới khoá `ai_box_calib:{slug}`, giá trị JSON của [BoxCalibration].
 * Áp dụng: `x' = x*scaleX + offsetX`, `y' = y*scaleY + offsetY` (toạ độ đã chuẩn hoá 0..1),
 * `w' = w*scaleX`, `h' = h*scaleY`. Mặc định `scaleX=scaleY=1, offsetX=offsetY=0` (không đổi gì).
 */
object BoxCalibrationService {

    @Serializable
    data class BoxCalibration(
        val offsetX: Double = 0.0,
        val offsetY: Double = 0.0,
        val scaleX: Double = 1.0,
        val scaleY: Double = 1.0,
    )

    private const val KEY_PREFIX = "ai_box_calib:"
    private val json = Json { ignoreUnknownKeys = true }

    fun get(slug: String): BoxCalibration {
        val raw = AppSettingsService.get(KEY_PREFIX + slug, "")
        if (raw.isBlank()) return BoxCalibration()
        return runCatching { json.decodeFromString<BoxCalibration>(raw) }.getOrElse {
            AppLogger.warn(AppLogger.Module.SYSTEM, "BOX_CALIB", "PARSE_FAIL", "slug=$slug err=${it.message}")
            BoxCalibration()
        }
    }

    fun set(slug: String, calib: BoxCalibration) {
        AppSettingsService.set(KEY_PREFIX + slug, json.encodeToString(BoxCalibration.serializer(), calib))
        AppLogger.info(AppLogger.Module.SYSTEM, "BOX_CALIB", "UPDATED",
            "slug=$slug offset=(${calib.offsetX},${calib.offsetY}) scale=(${calib.scaleX},${calib.scaleY})")
    }
}
