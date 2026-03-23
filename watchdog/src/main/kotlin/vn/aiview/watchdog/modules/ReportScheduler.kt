package vn.aiview.watchdog.modules

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import vn.aiview.watchdog.model.WatchdogConfig
import java.io.File
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ReportScheduler(
    private val config: WatchdogConfig,
    private val alertManager: AlertManager,
    private val db: EventLogger
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val healthMonitor = SystemHealthMonitor(config, kotlinx.coroutines.channels.Channel(1), db,
        SelfHealingEngine(config, alertManager, db))
    private var lastReportDate: java.time.LocalDate? = null

    suspend fun run() {
        while (true) {
            try {
                val tz = ZoneId.of(config.schedule.timezone)
                val now = ZonedDateTime.now(tz)

                val isReportTime = now.hour == config.schedule.report_hour
                        && now.minute == config.schedule.report_minute
                        && now.toLocalDate() != lastReportDate

                if (isReportTime) {
                    lastReportDate = now.toLocalDate()
                    sendDailyReport()
                }
            } catch (e: Exception) {
                log.error("ReportScheduler error: ${e.message}")
            }
            delay(30_000)
        }
    }

    fun sendDailyReport() {
        log.info("Generating daily report...")
        db.pruneOldLogs()

        val cpuPct = healthMonitor.readCpuPercent()
        val ramPct = healthMonitor.readRamPercent()
        val diskPct = healthMonitor.readDiskPercent("/recordings")
        val tempC = healthMonitor.readCpuTemp()

        val events24h = db.getEvents(24)
        val downtimeSeconds = db.getDowntimeSeconds(24)
        val uptimePct = ((86400.0 - downtimeSeconds) / 86400.0 * 100).coerceIn(0.0, 100.0)
        val incidentCount = events24h.count { it.type == EventType.NVR_DOWN }
        val restartCount = events24h.count { it.type == EventType.NVR_RESTARTED || it.type == EventType.CONTAINER_RESTARTED }
        val activeIncident = events24h.any { it.type == EventType.NVR_DOWN && !it.resolved }

        val tz = ZoneId.of(config.schedule.timezone)
        val dateStr = ZonedDateTime.now(tz).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))

        val msg = buildString {
            append("📊 <b>BÁO CÁO NVR HẰNG NGÀY</b>\n")
            append("🕒 $dateStr\n\n")
            append("━━━━━━━━━━━━━━━━━━━━━━\n")
            append("⚙️ <b>Tài nguyên hiện tại:</b>\n")
            append("  🖥️ CPU: <b>$cpuPct%</b>\n")
            append("  💾 RAM: <b>$ramPct%</b>\n")
            append("  💿 Disk: <b>$diskPct%</b>\n")
            if (tempC != null) append("  🌡️ Nhiệt: <b>${tempC}°C</b>\n")
            append("\n")
            append("📈 <b>Tình trạng 24h qua:</b>\n")
            append("  ✅ Uptime: <b>${String.format("%.2f", uptimePct)}%</b>\n")
            append("  ⏱️ Thời gian chết: <b>${downtimeSeconds / 60} phút</b>\n")
            append("  🚨 Sự cố: <b>$incidentCount lần</b>\n")
            append("  🔄 Restart: <b>$restartCount lần</b>\n")
            append("  📍 Hiện tại: <b>${if (activeIncident) "⛔ Đang sự cố" else "✅ Ổn định"}</b>\n")
        }

        // Save to file
        val reportDir = File("/data/reports")
        reportDir.mkdirs()
        val tz2 = ZoneId.of(config.schedule.timezone)
        val dateName = ZonedDateTime.now(tz2).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        File("${reportDir}/report_$dateName.json").writeText(
            """{"cpu":$cpuPct,"ram":$ramPct,"disk":$diskPct,"uptime_pct":$uptimePct,"incidents":$incidentCount,"downtime_seconds":$downtimeSeconds,"generated":"$dateStr"}"""
        )

        alertManager.sendTelegram(msg)
        db.log(EventType.ALERT_SENT, "Daily report sent")
    }

    fun sendUpdateReport(newVersion: String, success: Boolean, reason: String? = null) {
        val msg = if (success)
            "🆕 <b>Cập nhật thành công!</b>\n📦 Version mới: <code>$newVersion</code>"
        else
            "❌ <b>Cập nhật thất bại</b>\n📦 Version: <code>$newVersion</code>\n⚠️ Lý do: ${reason ?: "unknown"}"
        alertManager.sendAlert(AlertEvent(
            if (success) AlertType.UPDATE_SUCCESS else AlertType.UPDATE_FAILED, msg
        ))
    }

    fun sendPowerRestoredReport() {
        val msg = "⚡ <b>Nguồn điện đã khôi phục</b>\n🔄 Hệ thống NVR đã khởi động lại thành công"
        alertManager.sendAlert(AlertEvent(AlertType.SYSTEM_RECOVERED, msg))
    }
}
