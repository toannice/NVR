package vn.aiview.watchdog

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import vn.aiview.watchdog.model.WatchdogConfig
import vn.aiview.watchdog.model.loadConfig
import vn.aiview.watchdog.modules.*
import java.time.Instant

val logger = LoggerFactory.getLogger("WatchdogMain")

fun main() = runBlocking {
    logger.info("╔══════════════════════════════════════╗")
    logger.info("║       NVR Watchdog v1.0 starting     ║")
    logger.info("╚══════════════════════════════════════╝")

    val config: WatchdogConfig = loadConfig("/config/watchdog.yml")
    val alertChannel = Channel<AlertEvent>(capacity = 64)

    val db = EventLogger(config)
    db.init()
    db.log(EventType.SYSTEM_STARTUP, "Watchdog daemon started", severity = Severity.INFO)

    val alertManager = AlertManager(config, db)
    alertManager.sendTelegram("🚀 <b>Hệ thống NVR Watchdog đã khởi động</b>\nLịch thông báo: 9:00 AM hằng ngày.")
    val selfHealing = SelfHealingEngine(config, alertManager, db)
    val healthMonitor = SystemHealthMonitor(config, alertChannel, db, selfHealing)
    val reportScheduler = ReportScheduler(config, alertManager, db)
    // UpdateManager (registry pull) đã bị park bằng DISABLE_OTA — thay bằng OtaOrchestrator:
    // gói ký số, verify Ed25519, 4 gate chính sách, bàn giao cho host script để swap + rollback.
    // Xem watchdog/.../ota/OtaOrchestrator.kt về phân vai wrapper / watchdog / host.
    val otaOrchestrator = vn.aiview.watchdog.ota.OtaOrchestrator(config, alertManager, db)
    val networkMonitor = NetworkMonitor(config, alertManager, db)

    // Coroutine xử lý alert queue
    launch { alertManager.processAlerts(alertChannel) }

    // 5 vòng lặp giám sát song song
    val jobs = listOf(
        launch { healthMonitor.run() },
        launch { reportScheduler.run() },
        launch { otaOrchestrator.run() },
        launch { networkMonitor.run() }
    )

    // Shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking {
            logger.info("SIGTERM received — logging shutdown event")
            db.log(EventType.SYSTEM_SHUTDOWN, "Watchdog received SIGTERM, shutting down", severity = Severity.INFO)
            jobs.forEach { it.cancel() }
        }
    })

    jobs.joinAll()
}
