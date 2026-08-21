package vn.aiview.nvr.services

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import vn.aiview.nvr.models.AppSettings
import vn.aiview.nvr.models.Cameras

/**
 * Trạng thái AI View NVR không thể đặt trong `config.yml` (schema Frigate cấm key lạ) nên nằm ở
 * SQLite. Gom vào một chỗ để route nào cũng đọc/ghi cùng một cách.
 */
object AiConfigState {

    const val INTENT_UNSET = "unset"
    const val INTENT_ON = "on"
    const val INTENT_OFF = "off"

    /** Tên vùng mặc định hệ thống cấp cho camera mới. */
    const val DEFAULT_ZONE_NAME = "Toàn cảnh"

    /** Đối tượng vùng mặc định bắt: Người + Phương tiện — đúng hai nhóm AI View NVR hỗ trợ. */
    val DEFAULT_ZONE_OBJECTS = listOf("person", "car", "motorcycle", "bus", "truck")

    /** Khung hình đầy đủ, kẹp 0.999 để không sinh token "1.000" (xem sanitizeZoneCoordinates). */
    const val DEFAULT_ZONE_COORDINATES = "0.000,0.000,0.999,0.000,0.999,0.999,0.000,0.999"

    private const val KEY_DIRTY = "ai_config_dirty"

    // ── Cờ "có thay đổi chưa áp dụng" ────────────────────────────────────────────────────────
    // Lưu xuống DB chứ không giữ trong RAM: người dùng Lưu rồi mới bấm Áp dụng, ở giữa có thể
    // restart nvr-wrapper (OTA đêm chẳng hạn) — mất cờ thì banner biến mất và thay đổi nằm trong
    // config.yml mà chẳng ai biết là chưa áp dụng.

    fun isDirty(): Boolean = transaction {
        AppSettings.selectAll().where { AppSettings.key eq KEY_DIRTY }
            .firstOrNull()?.get(AppSettings.value) == "true"
    }

    fun markDirty() = setDirty(true)

    fun clearDirty() = setDirty(false)

    private fun setDirty(value: Boolean) {
        transaction {
            val exists = AppSettings.selectAll().where { AppSettings.key eq KEY_DIRTY }.firstOrNull() != null
            if (exists) {
                AppSettings.update({ AppSettings.key eq KEY_DIRTY }) { it[AppSettings.value] = value.toString() }
            } else {
                AppSettings.insert { it[key] = KEY_DIRTY; it[AppSettings.value] = value.toString() }
            }
        }
    }

    // ── Ý định bật/tắt AI của từng camera ────────────────────────────────────────────────────

    fun intentOf(slug: String): String = transaction {
        Cameras.selectAll().where { Cameras.slug eq slug }
            .firstOrNull()?.get(Cameras.aiIntent) ?: INTENT_UNSET
    }

    fun setIntent(slug: String, intent: String) {
        transaction {
            Cameras.update({ Cameras.slug eq slug }) { it[aiIntent] = intent }
        }
    }

    fun isDefaultZoneUntouched(slug: String): Boolean = transaction {
        Cameras.selectAll().where { Cameras.slug eq slug }
            .firstOrNull()?.get(Cameras.aiDefaultZone) ?: false
    }

    fun setDefaultZoneFlag(slug: String, value: Boolean) {
        transaction {
            Cameras.update({ Cameras.slug eq slug }) { it[aiDefaultZone] = value }
        }
    }

    /** Camera chưa ai cấu hình AI — ứng viên được đề nghị cấp vùng Toàn cảnh khi bật AI tổng. */
    fun slugsWithIntent(intent: String): List<String> = transaction {
        Cameras.selectAll().where { Cameras.aiIntent eq intent }.map { it[Cameras.slug] }
    }

    /** Khối zone mặc định để ghi thẳng vào config.yml. */
    fun defaultZoneBlock(): Map<String, Any> = mapOf(
        DEFAULT_ZONE_NAME to mapOf(
            "coordinates" to DEFAULT_ZONE_COORDINATES,
            "objects" to DEFAULT_ZONE_OBJECTS,
        )
    )
}
