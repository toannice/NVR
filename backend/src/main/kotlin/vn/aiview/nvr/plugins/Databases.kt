package vn.aiview.nvr.plugins

import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.insert
import java.io.File
import vn.aiview.nvr.models.*
import vn.aiview.nvr.utils.AppLogger
import vn.aiview.nvr.utils.CryptoUtils

fun Application.configureDatabases() {
    val dataDir = File("data")
    if (!dataDir.exists()) {
        dataDir.mkdirs()
    }

    CryptoUtils.initDeviceKey()

    // SQLite with WAL mode and configurations
    val url = "jdbc:sqlite:data/nvr.db?journal_mode=WAL&synchronous=1&cache_size=-16000&foreign_keys=on"
    val database = Database.connect(url, driver = "org.sqlite.JDBC")

    transaction(database) {
        // Auto-create/update tables (Exposed issues ALTER TABLE ADD COLUMN for missing nullable/defaulted columns)
        SchemaUtils.createMissingTablesAndColumns(
            Cameras,
            Schedules,
            LockConfig,
            OtpTokens,
            AIEvents,
            CloudConfig,
            CloudCameras,
            Gateways,
            IntegrationSettings,
            IntegrationConnections,
            AppSettings
        )

        migrateCloudCamerasIntoCameras()
        seedIntegrationSettings()
    }
}

/**
 * Ensure the single-row integration_settings row (id=1) exists, seeded with the factory-default
 * Box credentials (username "admin" / password "Bkav@2020", bcrypt-hashed) and enabled.
 * Idempotent — only inserts when the row is missing, so an admin's later changes are never clobbered.
 */
private const val INTEGRATION_DEFAULT_USERNAME = "admin"
private const val INTEGRATION_DEFAULT_PASSWORD = "Bkav@2020"

private fun seedIntegrationSettings() {
    try {
        val row = IntegrationSettings.selectAll().where { IntegrationSettings.id eq 1 }.firstOrNull()
        if (row == null) {
            IntegrationSettings.insert {
                it[id] = 1
                it[enabled] = true
                it[username] = INTEGRATION_DEFAULT_USERNAME
                it[passwordHash] = hashPattern(INTEGRATION_DEFAULT_PASSWORD)
                it[passwordLen] = INTEGRATION_DEFAULT_PASSWORD.length
            }
            AppLogger.info(AppLogger.Module.SYSTEM, "DB", "INTEGRATION_SEED", "integration_settings row id=1 created (default admin/Bkav@2020, enabled)")
        } else if (row[IntegrationSettings.passwordLen] == 0 && row[IntegrationSettings.passwordHash].isNotBlank()) {
            // Migration backfill: the new password_len column defaults to 0 on pre-existing rows.
            // We can't recover an arbitrary password's length from its hash, but the very common
            // case is the untouched factory default — detect it and restore its length so the
            // masked •••• UI hint is correct. (Custom passwords stay 0 until next change.)
            val (isDefault, _) = verifyPattern(INTEGRATION_DEFAULT_PASSWORD, row[IntegrationSettings.passwordHash])
            if (isDefault) {
                IntegrationSettings.update({ IntegrationSettings.id eq 1 }) {
                    it[passwordLen] = INTEGRATION_DEFAULT_PASSWORD.length
                }
                AppLogger.info(AppLogger.Module.SYSTEM, "DB", "INTEGRATION_BACKFILL", "password_len backfilled to default length")
            }
        }
    } catch (e: Exception) {
        AppLogger.warn(AppLogger.Module.SYSTEM, "DB", "INTEGRATION_SEED_FAIL", e.message ?: "", e)
    }
}

/**
 * 003-cloud-data-sync: one-shot copy of cloud_cameras rows into the unified cameras table.
 * Idempotent — re-runs safely; a row already merged into cameras is detected by matching channel_id.
 */
private fun migrateCloudCamerasIntoCameras() {
    var inserted = 0
    var merged = 0
    try {
        val cloudRows = CloudCameras.selectAll().toList()
        if (cloudRows.isEmpty()) return

        for (row in cloudRows) {
            val chId = row[CloudCameras.channelId]
            val host = row[CloudCameras.ip]
            val port = row[CloudCameras.port]
            val plaintextPassword = row[CloudCameras.password]
            val encryptedPassword = if (plaintextPassword.isNotEmpty()) CryptoUtils.encrypt(plaintextPassword) else null

            // Match by channel_id first (if already merged), else by ip+onvif_port (legacy)
            val existing = Cameras.selectAll().where {
                (Cameras.channelId eq chId) or
                    ((Cameras.ip eq host) and (Cameras.onvifPort eq port))
            }.firstOrNull()

            if (existing != null) {
                if (existing[Cameras.channelId] == chId &&
                    existing[Cameras.mainToken] == row[CloudCameras.mainToken] &&
                    existing[Cameras.rtspUrl] == row[CloudCameras.rtspUrl]
                ) continue // already merged, skip
                Cameras.update({ Cameras.slug eq existing[Cameras.slug] }) {
                    it[Cameras.channelId] = chId
                    it[Cameras.username] = row[CloudCameras.username]
                    it[Cameras.passwordEncrypted] = encryptedPassword
                    it[Cameras.mainToken] = row[CloudCameras.mainToken]
                    it[Cameras.subToken] = row[CloudCameras.subToken]
                    it[Cameras.rtspUrl] = row[CloudCameras.rtspUrl]
                    it[Cameras.subRtspUrl] = row[CloudCameras.subRtspUrl]
                    it[Cameras.macAddress] = row[CloudCameras.macAddress]
                    it[Cameras.hasPtz] = row[CloudCameras.hasPtz]
                }
                merged++
            } else {
                val newSlug = "cam_$chId"
                if (Cameras.selectAll().where { Cameras.slug eq newSlug }.firstOrNull() != null) continue
                Cameras.insert {
                    it[Cameras.slug] = newSlug
                    it[Cameras.displayName] = row[CloudCameras.name].ifBlank { "Camera $chId" }
                    it[Cameras.ip] = host
                    it[Cameras.onvifPort] = port
                    it[Cameras.ptzEnabled] = row[CloudCameras.hasPtz]
                    it[Cameras.channelId] = chId
                    it[Cameras.username] = row[CloudCameras.username]
                    it[Cameras.passwordEncrypted] = encryptedPassword
                    it[Cameras.mainToken] = row[CloudCameras.mainToken]
                    it[Cameras.subToken] = row[CloudCameras.subToken]
                    it[Cameras.rtspUrl] = row[CloudCameras.rtspUrl]
                    it[Cameras.subRtspUrl] = row[CloudCameras.subRtspUrl]
                    it[Cameras.macAddress] = row[CloudCameras.macAddress]
                    it[Cameras.hasPtz] = row[CloudCameras.hasPtz]
                }
                inserted++
            }
        }
    } catch (e: Exception) {
        AppLogger.warn(AppLogger.Module.SYSTEM, "DB", "MIGRATION_CLOUD_CAMERAS_FAIL", e.message ?: "", e)
        return
    }
    if (inserted > 0 || merged > 0) {
        AppLogger.info(AppLogger.Module.SYSTEM, "DB", "MIGRATION_CLOUD_CAMERAS", "inserted=$inserted merged=$merged")
    }
}

