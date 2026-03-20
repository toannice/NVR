package vn.aiview.watchdog.modules

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.javatime.datetime
import org.slf4j.LoggerFactory
import vn.aiview.watchdog.config.WatchdogConfig
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

enum class EventType {
    SYSTEM_STARTUP, SYSTEM_SHUTDOWN,
    NVR_DOWN, NVR_RESTARTED,
    CONTAINER_DOWN, CONTAINER_RESTARTED,
    CPU_HIGH, RAM_HIGH, DISK_HIGH, TEMP_HIGH,
    DISK_CLEANUP,
    NETWORK_DOWN, NETWORK_RESTORED,
    UPDATE_STARTED, UPDATE_SUCCESS, UPDATE_FAILED, ROLLBACK_SUCCESS, ROLLBACK_FAILED,
    ALERT_SENT
}

enum class Severity { INFO, WARN, ERROR, CRITICAL }

data class WatchdogEvent(
    val id: Long,
    val type: EventType,
    val message: String,
    val details: String?,
    val timestamp: LocalDateTime,
    val severity: Severity,
    val resolved: Boolean
)

object Events : LongIdTable("events") {
    val type = varchar("type", 64)
    val message = varchar("message", 512)
    val details = text("details").nullable()
    val timestamp = datetime("timestamp")
    val severity = varchar("severity", 16)
    val resolved = bool("resolved").default(false)
}

class EventLogger(private val config: WatchdogConfig) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val logFile = File("/data/watchdog.log")
    private val dbPath = "/data/events.db"

    fun init() {
        Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(Events)
        }
        logFile.parentFile?.mkdirs()
        log.info("EventLogger initialized — DB: $dbPath, Log: ${logFile.absolutePath}")
    }

    fun log(type: EventType, message: String, details: String? = null, severity: Severity = Severity.INFO) {
        val now = LocalDateTime.now()
        try {
            transaction {
                Events.insert {
                    it[Events.type] = type.name
                    it[Events.message] = message
                    it[Events.details] = details
                    it[Events.timestamp] = now
                    it[Events.severity] = severity.name
                    it[Events.resolved] = false
                }
            }
        } catch (e: Exception) {
            log.error("Failed to write event to DB: ${e.message}")
        }

        val line = "[$now] [${severity.name}] [${type.name}] $message"
        try {
            logFile.appendText(line + "\n")
        } catch (e: Exception) { /* ignore */ }

        when (severity) {
            Severity.ERROR, Severity.CRITICAL -> log.error("EVENT: $line")
            Severity.WARN -> log.warn("EVENT: $line")
            else -> log.info("EVENT: $line")
        }
    }

    fun resolveEvent(type: EventType) {
        try {
            transaction {
                Events.update({ (Events.type eq type.name) and (Events.resolved eq false) }) {
                    it[resolved] = true
                }
            }
        } catch (e: Exception) { /* ignore */ }
    }

    fun getEvents(sinceHours: Long = 24): List<WatchdogEvent> {
        val since = LocalDateTime.now().minusHours(sinceHours)
        return try {
            transaction {
                Events.select { Events.timestamp greaterEq since }
                    .orderBy(Events.timestamp, SortOrder.ASC)
                    .map {
                        WatchdogEvent(
                            id = it[Events.id].value,
                            type = EventType.valueOf(it[Events.type]),
                            message = it[Events.message],
                            details = it[Events.details],
                            timestamp = it[Events.timestamp],
                            severity = Severity.valueOf(it[Events.severity]),
                            resolved = it[Events.resolved]
                        )
                    }
            }
        } catch (e: Exception) { emptyList() }
    }

    fun getDowntimeSeconds(sinceHours: Long = 24): Long {
        val events = getEvents(sinceHours)
        var downtime = 0L
        var downSince: LocalDateTime? = null

        for (event in events) {
            if (event.type == EventType.NVR_DOWN && downSince == null) {
                downSince = event.timestamp
            } else if (event.type == EventType.NVR_RESTARTED && downSince != null) {
                downtime += java.time.Duration.between(downSince, event.timestamp).seconds
                downSince = null
            }
        }

        // Still down
        if (downSince != null) {
            downtime += java.time.Duration.between(downSince, LocalDateTime.now()).seconds
        }

        return downtime
    }

    fun pruneOldLogs() {
        val cutoff = LocalDateTime.now().minusDays(config.log.keep_days.toLong())
        try {
            transaction {
                Events.deleteWhere { Events.timestamp less cutoff }
            }
        } catch (e: Exception) { /* ignore */ }
    }
}
