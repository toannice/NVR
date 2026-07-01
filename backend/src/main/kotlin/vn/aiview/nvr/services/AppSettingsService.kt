package vn.aiview.nvr.services

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import vn.aiview.nvr.models.AppSettings
import vn.aiview.nvr.utils.AppLogger

/**
 * Thin key/value accessor over the app_settings table. Currently used for `ai_source_mode`
 * (5B mode): "all" shows every AI-event source, "box_only" hides/skips Frigate AI events while
 * leaving Frigate detect + motion recording untouched.
 */
object AppSettingsService {

    const val KEY_AI_SOURCE_MODE = "ai_source_mode"
    const val MODE_ALL = "all"
    const val MODE_BOX_ONLY = "box_only"

    fun get(key: String, default: String = ""): String = try {
        transaction {
            AppSettings.selectAll().where { AppSettings.key eq key }.firstOrNull()?.get(AppSettings.value) ?: default
        }
    } catch (e: Exception) {
        AppLogger.warn(AppLogger.Module.SYSTEM, "SETTINGS", "GET_FAIL", "key=$key err=${e.message}")
        default
    }

    fun set(key: String, value: String) = transaction {
        val exists = AppSettings.selectAll().where { AppSettings.key eq key }.firstOrNull() != null
        if (exists) {
            AppSettings.update({ AppSettings.key eq key }) {
                it[AppSettings.value] = value
                it[AppSettings.updatedAt] = CurrentDateTime
            }
        } else {
            AppSettings.insert {
                it[AppSettings.key] = key
                it[AppSettings.value] = value
            }
        }
    }

    /** True when only Box-pushed AI events should be surfaced (5B mode). */
    fun isBoxOnly(): Boolean = get(KEY_AI_SOURCE_MODE, MODE_ALL) == MODE_BOX_ONLY
}
