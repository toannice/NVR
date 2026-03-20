package vn.aiview.watchdog.modules

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import vn.aiview.watchdog.config.WatchdogConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileSystems
import java.time.Instant

class SystemHealthMonitor(
    private val config: WatchdogConfig,
    private val alertChannel: kotlinx.coroutines.channels.Channel<AlertEvent>,
    private val db: EventLogger,
    private val selfHealing: SelfHealingEngine
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val docker = DockerApi()

    // Track consecutive failures per container
    private val consecutiveFailures = mutableMapOf<String, Int>()
    // Track consecutive threshold violations per metric
    private val cpuViolations = mutableMapOf<String, Int>()

    suspend fun run() {
        while (true) {
            try {
                runChecks()
            } catch (e: Exception) {
                log.error("SystemHealthMonitor error: ${e.message}")
            }
            delay(30_000)
        }
    }

    private suspend fun runChecks() {
        val cpuPct = readCpuPercent()
        val ramPct = readRamPercent()
        val diskPct = readDiskPercent("/recordings")
        val tempC = readCpuTemp()

        log.info("Health | CPU: $cpuPct% | RAM: $ramPct% | Disk: $diskPct% | Temp: ${tempC ?: "N/A"}°C")

        // CPU check
        checkThreshold("cpu", cpuPct, config.thresholds.cpu_percent) {
            AlertEvent(AlertType.CPU_HIGH, "🌡️ CPU cao: $cpuPct% (ngưỡng ${config.thresholds.cpu_percent}%)")
        }

        // RAM check
        checkThreshold("ram", ramPct, config.thresholds.ram_percent) {
            AlertEvent(AlertType.RAM_HIGH, "💾 RAM cao: $ramPct% (ngưỡng ${config.thresholds.ram_percent}%)")
        }

        // Disk check
        checkThreshold("disk", diskPct, config.thresholds.disk_percent) {
            AlertEvent(AlertType.DISK_HIGH, "💿 Disk cao: $diskPct% (ngưỡng ${config.thresholds.disk_percent}%)")
        }

        // Disk cleanup trigger (lower threshold than alert)
        if (diskPct >= config.self_healing.disk_cleanup_trigger_percent) {
            selfHealing.handleDiskFull(diskPct)
        }

        // Temperature check
        if (tempC != null) {
            checkThreshold("temp", tempC, config.thresholds.temp_celsius) {
                AlertEvent(AlertType.TEMP_HIGH, "🔥 Nhiệt độ cao: ${tempC}°C (ngưỡng ${config.thresholds.temp_celsius}°C)")
            }
        }

        // Container health checks
        for (container in config.containers) {
            checkContainer(container.name)
        }
    }

    private suspend fun checkThreshold(key: String, value: Int, threshold: Int, alertFactory: () -> AlertEvent) {
        if (value > threshold) {
            val count = (cpuViolations[key] ?: 0) + 1
            cpuViolations[key] = count
            if (count >= config.thresholds.consecutive_failures) {
                cpuViolations[key] = 0
                val event = alertFactory()
                db.log(event.type.toEventType(), event.message, severity = Severity.WARN)
                alertChannel.trySend(event)
            }
        } else {
            cpuViolations[key] = 0
        }
    }

    private suspend fun checkContainer(name: String) {
        val state = docker.getContainerState(name)
        val isRunning = state == "running"
        val isHealthy = state == "healthy"

        if (!isRunning) {
            val count = (consecutiveFailures[name] ?: 0) + 1
            consecutiveFailures[name] = count
            if (count >= config.thresholds.consecutive_failures) {
                consecutiveFailures[name] = 0
                log.warn("Container '$name' is down (state=$state) — triggering self-healing")
                db.log(EventType.NVR_DOWN, "Container $name is $state", severity = Severity.ERROR)
                alertChannel.trySend(AlertEvent(AlertType.CONTAINER_DOWN, "🔴 Container <b>$name</b> ngừng hoạt động (state: $state)"))
                selfHealing.handleContainerDown(name)
            }
        } else {
            if ((consecutiveFailures[name] ?: 0) > 0) {
                consecutiveFailures[name] = 0
            }
        }
    }

    // ── Đọc CPU từ /proc/stat (2 lần cách nhau 500ms) ──
    fun readCpuPercent(): Int {
        fun readStat(): List<Long> {
            val line = File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") } ?: return emptyList()
            return line.split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
        }
        val s1 = readStat(); Thread.sleep(500); val s2 = readStat()
        if (s1.isEmpty() || s2.isEmpty()) return 0
        val idle1 = s1[3]; val idle2 = s2[3]
        val total1 = s1.sum(); val total2 = s2.sum()
        val totalDiff = (total2 - total1).toDouble()
        val idleDiff = (idle2 - idle1).toDouble()
        return if (totalDiff > 0) ((1.0 - idleDiff / totalDiff) * 100).toInt() else 0
    }

    // ── Đọc RAM từ /proc/meminfo ──
    fun readRamPercent(): Int {
        val lines = File("/proc/meminfo").readLines()
        fun getValue(key: String) = lines.firstOrNull { it.startsWith(key) }
            ?.split("\\s+".toRegex())?.getOrNull(1)?.toLongOrNull() ?: 0L
        val total = getValue("MemTotal:")
        val available = getValue("MemAvailable:")
        return if (total > 0) ((1.0 - available.toDouble() / total) * 100).toInt() else 0
    }

    // ── Đọc Disk từ filesystem ──
    fun readDiskPercent(path: String): Int {
        return try {
            val store = FileSystems.getDefault().getFileStores().firstOrNull { fs ->
                fs.name().contains("recordings") || path.contains(fs.name())
            } ?: FileSystems.getDefault().getFileStores().first()
            val total = store.totalSpace
            val usable = store.usableSpace
            if (total > 0) ((1.0 - usable.toDouble() / total) * 100).toInt() else 0
        } catch (e: Exception) { 0 }
    }

    // ── Đọc nhiệt độ từ /sys/class/thermal ──
    fun readCpuTemp(): Int? {
        return try {
            File("/sys/class/thermal").listFiles()
                ?.filter { it.name.startsWith("thermal_zone") }
                ?.mapNotNull { zone ->
                    File(zone, "temp").readText().trim().toLongOrNull()?.div(1000)?.toInt()
                }?.maxOrNull()
        } catch (e: Exception) { null }
    }
}
