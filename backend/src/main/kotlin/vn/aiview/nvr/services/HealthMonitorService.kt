package vn.aiview.nvr.services

import kotlinx.coroutines.*
import vn.aiview.nvr.utils.AppLogger
import java.io.File

object HealthMonitorService {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private const val CPU_THRESHOLD = 90.0
    private const val RAM_THRESHOLD = 90.0
    private const val TEMP_THRESHOLD = 70.0
    private const val POLL_MS = 5_000L

    private var prevIdle = 0L
    private var prevTotal = 0L

    fun start() {
        scope.launch {
            AppLogger.info(AppLogger.Module.HEALTH, "MONITOR", "START", "poll=${POLL_MS}ms cpu>$CPU_THRESHOLD% ram>$RAM_THRESHOLD% temp>${TEMP_THRESHOLD}°C")
            while (isActive) {
                try {
                    val cpu = readCpuPercent()
                    val (ramUsedMb, ramTotalMb) = readRamMb()
                    val ramPct = if (ramTotalMb > 0) ramUsedMb * 100.0 / ramTotalMb else 0.0
                    val tempC = readTempC()

                    val netKb = readNetKb()
                    val diskKb = readDiskKb()

                    AppLogger.info(
                        AppLogger.Module.HEALTH, "MONITOR", "STATS",
                        "CPU=${fmt1(cpu)}%  RAM=${fmt1(ramPct)}% (${ramUsedMb}/${ramTotalMb}MB)" +
                        (if (tempC != null) "  TEMP=${fmt1(tempC)}°C" else "") +
                        "  NET=↓${netKb.first}KB ↑${netKb.second}KB  DISK=R${diskKb.first}KB W${diskKb.second}KB"
                    )

                    if (cpu > CPU_THRESHOLD)
                        AppLogger.error(AppLogger.Module.HEALTH, "MONITOR", "THRESHOLD_EXCEEDED", "CPU=${fmt1(cpu)}% > $CPU_THRESHOLD%")
                    if (ramPct > RAM_THRESHOLD)
                        AppLogger.error(AppLogger.Module.HEALTH, "MONITOR", "THRESHOLD_EXCEEDED", "RAM=${fmt1(ramPct)}% (${ramUsedMb}/${ramTotalMb}MB) > $RAM_THRESHOLD%")
                    if (tempC != null && tempC > TEMP_THRESHOLD)
                        AppLogger.error(AppLogger.Module.HEALTH, "MONITOR", "THRESHOLD_EXCEEDED", "TEMP=${fmt1(tempC)}°C > $TEMP_THRESHOLD°C")

                } catch (e: Exception) {
                    AppLogger.warn(AppLogger.Module.HEALTH, "MONITOR", "READ_ERROR", e.message ?: "unknown")
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() = scope.cancel()

    // ── Readers ────────────────────────────────────────────────────────────

    private fun readCpuPercent(): Double {
        val line = File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") } ?: return 0.0
        val parts = line.trim().split("\\s+".toRegex()).drop(1).map { it.toLongOrNull() ?: 0L }
        if (parts.size < 4) return 0.0
        val idle = parts[3] + (parts.getOrElse(4) { 0L })
        val total = parts.sum()
        val diffIdle = idle - prevIdle
        val diffTotal = total - prevTotal
        prevIdle = idle; prevTotal = total
        return if (diffTotal == 0L) 0.0 else (1.0 - diffIdle.toDouble() / diffTotal) * 100.0
    }

    private fun readRamMb(): Pair<Long, Long> {
        var total = 0L; var available = 0L
        File("/proc/meminfo").forEachLine { line ->
            when {
                line.startsWith("MemTotal:")     -> total     = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
                line.startsWith("MemAvailable:")  -> available = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
            }
        }
        val totalMb = total / 1024
        val usedMb  = (total - available) / 1024
        return usedMb to totalMb
    }

    private fun readTempC(): Double? {
        val thermalDir = File("/sys/class/thermal")
        if (!thermalDir.exists()) return null
        return thermalDir.listFiles { f -> f.name.startsWith("thermal_zone") }
            ?.mapNotNull { zone ->
                val tempFile = File(zone, "temp")
                if (tempFile.exists()) tempFile.readText().trim().toLongOrNull()?.div(1000.0) else null
            }
            ?.maxOrNull()
    }

    private var prevNetRx = 0L; private var prevNetTx = 0L
    private fun readNetKb(): Pair<Long, Long> {
        var rx = 0L; var tx = 0L
        File("/proc/net/dev").forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("eth") || trimmed.startsWith("enp") || trimmed.startsWith("ens") || trimmed.startsWith("wlan")) {
                val parts = trimmed.split("\\s+".toRegex())
                rx += parts.getOrElse(1) { "0" }.toLongOrNull() ?: 0L
                tx += parts.getOrElse(9) { "0" }.toLongOrNull() ?: 0L
            }
        }
        val rxKb = maxOf(0L, (rx - prevNetRx) / 1024)
        val txKb = maxOf(0L, (tx - prevNetTx) / 1024)
        prevNetRx = rx; prevNetTx = tx
        return rxKb to txKb
    }

    private var prevDiskR = 0L; private var prevDiskW = 0L
    private fun readDiskKb(): Pair<Long, Long> {
        var r = 0L; var w = 0L
        File("/proc/diskstats").forEachLine { line ->
            val parts = line.trim().split("\\s+".toRegex())
            val name = parts.getOrElse(2) { "" }
            if (name.matches(Regex("sd[a-z]|nvme\\d+n\\d+|vd[a-z]"))) {
                r += (parts.getOrElse(5) { "0" }.toLongOrNull() ?: 0L) * 512
                w += (parts.getOrElse(9) { "0" }.toLongOrNull() ?: 0L) * 512
            }
        }
        val rKb = maxOf(0L, (r - prevDiskR) / 1024)
        val wKb = maxOf(0L, (w - prevDiskW) / 1024)
        prevDiskR = r; prevDiskW = w
        return rKb to wKb
    }

    private fun fmt1(v: Double): String {
        val rounded = Math.round(v * 10)
        return "${rounded / 10}.${rounded % 10}"
    }
}
