package vn.aiview.watchdog.modules

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import vn.aiview.watchdog.config.WatchdogConfig
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

enum class AlertType {
    CPU_HIGH, RAM_HIGH, DISK_HIGH, TEMP_HIGH,
    CONTAINER_DOWN, CONTAINER_RESTARTED,
    NETWORK_DOWN, NETWORK_RESTORED,
    UPDATE_SUCCESS, UPDATE_FAILED, ROLLBACK_SUCCESS, ROLLBACK_FAILED,
    DISK_CLEANUP, SYSTEM_RECOVERED, CRITICAL
}

fun AlertType.toEventType(): EventType = when (this) {
    AlertType.CPU_HIGH -> EventType.CPU_HIGH
    AlertType.RAM_HIGH -> EventType.RAM_HIGH
    AlertType.DISK_HIGH -> EventType.DISK_HIGH
    AlertType.TEMP_HIGH -> EventType.TEMP_HIGH
    AlertType.CONTAINER_DOWN -> EventType.CONTAINER_DOWN
    AlertType.CONTAINER_RESTARTED -> EventType.CONTAINER_RESTARTED
    AlertType.NETWORK_DOWN -> EventType.NETWORK_DOWN
    AlertType.NETWORK_RESTORED -> EventType.NETWORK_RESTORED
    AlertType.UPDATE_SUCCESS -> EventType.UPDATE_SUCCESS
    AlertType.UPDATE_FAILED -> EventType.UPDATE_FAILED
    AlertType.ROLLBACK_SUCCESS -> EventType.ROLLBACK_SUCCESS
    AlertType.ROLLBACK_FAILED -> EventType.ROLLBACK_FAILED
    AlertType.DISK_CLEANUP -> EventType.DISK_CLEANUP
    AlertType.SYSTEM_RECOVERED -> EventType.NVR_RESTARTED
    AlertType.CRITICAL -> EventType.NVR_DOWN
}

data class AlertEvent(
    val type: AlertType,
    val message: String,
    val details: String? = null
)

class AlertManager(
    private val config: WatchdogConfig,
    private val db: EventLogger
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cooldowns = mutableMapOf<AlertType, Long>() // AlertType -> lastSentEpoch ms
    private val COOLDOWN_MS = 15 * 60 * 1000L  // 15 min
    private var alertsThisHour = 0
    private var hourBucket = 0L  // epoch hour

    suspend fun processAlerts(channel: Channel<AlertEvent>) {
        for (event in channel) {
            try {
                sendAlert(event)
            } catch (e: Exception) {
                log.error("AlertManager error: ${e.message}")
            }
        }
    }

    fun sendAlert(event: AlertEvent) {
        val now = Instant.now().toEpochMilli()
        val currentHour = now / 3_600_000

        // Reset hourly counter
        if (currentHour != hourBucket) {
            hourBucket = currentHour
            alertsThisHour = 0
        }

        // Rate limit
        if (alertsThisHour >= 20) {
            log.warn("Rate limit reached (20/hr). Dropping alert: ${event.type}")
            return
        }

        // Cooldown per type
        val lastSent = cooldowns[event.type] ?: 0L
        if (now - lastSent < COOLDOWN_MS) {
            log.debug("Cooldown active for ${event.type}, skipping")
            return
        }

        cooldowns[event.type] = now
        alertsThisHour++

        sendTelegram(event.message, event.details)
        db.log(EventType.ALERT_SENT, "Alert sent: ${event.type}", event.message)
    }

    fun sendTelegram(message: String, details: String? = null) {
        val botToken = config.telegram.bot_token
        val chatId = config.telegram.chat_id
        if (botToken.isBlank() || chatId.isBlank()) {
            log.warn("Telegram not configured, skipping alert")
            return
        }

        val hostname = System.getenv("HOSTNAME") ?: "nvr-edge"
        val body = buildString {
            append(message)
            if (details != null) append("\n\n<i>$details</i>")
            append("\n\n📍 <code>$hostname</code>")
        }

        try {
            val url = URL("https://api.telegram.org/bot$botToken/sendMessage")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = """{"chat_id":"$chatId","text":${jsonEscape(body)},"parse_mode":"HTML"}"""
            OutputStreamWriter(conn.outputStream).use { it.write(payload) }
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                log.warn("Telegram responded $responseCode for alert")
            } else {
                log.info("Telegram alert sent: $message")
            }
        } catch (e: Exception) {
            log.error("Failed to send Telegram: ${e.message}")
        }
    }

    private fun jsonEscape(s: String): String {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\""
    }
}
