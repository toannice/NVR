package vn.aiview.watchdog.modules

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import vn.aiview.watchdog.model.WatchdogConfig
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SelfHealingEngine(
    private val config: WatchdogConfig,
    private val alertManager: AlertManager,
    private val db: EventLogger
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val docker = DockerApi()

    // Track restart times per container within the current hour
    private val restartHistory = ConcurrentHashMap<String, MutableList<Long>>()

    suspend fun handleContainerDown(name: String) {
        val now = Instant.now().toEpochMilli()
        val hourAgo = now - 3_600_000L
        val history = restartHistory.getOrPut(name) { mutableListOf() }
        history.removeAll { it < hourAgo }

        if (history.size >= config.self_healing.max_restarts_per_hour) {
            val msg = "⛔ <b>$name</b> đã restart ${history.size} lần/giờ — cần can thiệp thủ công!"
            log.error(msg)
            alertManager.sendAlert(AlertEvent(AlertType.CRITICAL, msg))
            db.log(EventType.CONTAINER_DOWN, "Max restarts reached for $name — manual intervention needed", severity = Severity.CRITICAL)
            return
        }

        log.info("Restarting container $name (attempt ${history.size + 1}/${config.self_healing.max_restarts_per_hour} this hour)")
        db.log(EventType.NVR_DOWN, "Initiating restart of $name")

        val success = docker.restartContainer(name)
        history.add(now)

        if (success) {
            // Wait and verify
            delay(30_000)
            val state = docker.getContainerState(name)
            if (state == "running" || state == "healthy") {
                db.log(EventType.NVR_RESTARTED, "Container $name restarted successfully", severity = Severity.INFO)
                db.resolveEvent(EventType.NVR_DOWN)
                alertManager.sendAlert(AlertEvent(AlertType.CONTAINER_RESTARTED, "✅ <b>$name</b> đã restart thành công"))
            } else {
                db.log(EventType.CONTAINER_DOWN, "Container $name still not running after restart (state=$state)", severity = Severity.ERROR)
                alertManager.sendAlert(AlertEvent(AlertType.CONTAINER_DOWN, "❌ <b>$name</b> không khôi phục sau restart (state: $state)"))
            }
        } else {
            db.log(EventType.CONTAINER_DOWN, "docker restart $name failed", severity = Severity.ERROR)
        }
    }

    fun handleDiskFull(currentPercent: Int) {
        val targetPercent = config.self_healing.disk_cleanup_target_percent
        log.warn("Disk at $currentPercent% — cleaning /recordings down to $targetPercent%")
        db.log(EventType.DISK_CLEANUP, "Disk cleanup triggered at $currentPercent%", severity = Severity.WARN)

        val files = docker.listOldest("/recordings")
        var cleaned = 0
        var freedBytes = 0L

        val recordingsDir = java.io.File("/recordings")
        for (file in files) {
            val total = recordingsDir.totalSpace.takeIf { it > 0 } ?: 1L
            val usable = recordingsDir.usableSpace
            val pct = ((1.0 - usable.toDouble() / total) * 100).toInt()
            if (pct < targetPercent) break

            freedBytes += file.length()
            file.delete()
            cleaned++
        }

        val freedMB = freedBytes / 1_048_576
        val msg = "🧹 Đã dọn $cleaned file (~${freedMB}MB) khỏi /recordings"
        log.info(msg)
        alertManager.sendAlert(AlertEvent(AlertType.DISK_CLEANUP, msg))
        db.log(EventType.DISK_CLEANUP, msg)
    }
}
