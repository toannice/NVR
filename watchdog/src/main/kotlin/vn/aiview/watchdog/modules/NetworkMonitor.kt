package vn.aiview.watchdog.modules

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import vn.aiview.watchdog.config.WatchdogConfig
import java.io.IOException
import java.net.InetAddress

class NetworkMonitor(
    private val config: WatchdogConfig,
    private val alertManager: AlertManager,
    private val db: EventLogger
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var wasDown = false
    private var failCount = 0
    private val CHECK_INTERVAL_MS = 120_000L  // 2 minutes
    private val MAX_FAILS = 5

    suspend fun run() {
        while (true) {
            try {
                checkNetwork()
            } catch (e: Exception) {
                log.error("NetworkMonitor error: ${e.message}")
            }
            delay(CHECK_INTERVAL_MS)
        }
    }

    private fun checkNetwork() {
        val reachable = isReachable("8.8.8.8")

        if (!reachable) {
            failCount++
            log.warn("Network ping failed ($failCount/$MAX_FAILS)")

            if (failCount >= MAX_FAILS && !wasDown) {
                wasDown = true
                db.log(EventType.NETWORK_DOWN, "Network unreachable after $failCount attempts", severity = Severity.ERROR)
                alertManager.sendAlert(AlertEvent(AlertType.NETWORK_DOWN,
                    "📡 <b>Mất kết nối mạng!</b>\n🔁 Ping 8.8.8.8 thất bại $failCount lần liên tiếp\n⚠️ Kiểm tra cáp mạng / modem"))
                // Note: No eth0 reset (as per design decision — detect+alert only)
            }
        } else {
            if (wasDown) {
                wasDown = false
                failCount = 0
                db.log(EventType.NETWORK_RESTORED, "Network connection restored", severity = Severity.INFO)
                db.resolveEvent(EventType.NETWORK_DOWN)
                alertManager.sendAlert(AlertEvent(AlertType.NETWORK_RESTORED,
                    "✅ <b>Mạng đã kết nối lại!</b>"))
            } else {
                failCount = 0
            }
        }
    }

    private fun isReachable(host: String): Boolean {
        return try {
            InetAddress.getByName(host).isReachable(3000)
        } catch (e: IOException) {
            false
        }
    }
}
