package vn.aiview.nvr.services

import kotlinx.coroutines.*
import vn.aiview.nvr.gateway.GatewayConfig
import vn.aiview.nvr.utils.AppLogger
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Edge-triggered health watchdog for the overlay (watchdog step 4). Every minute it reads the gateway
 * list + HQ status and fires a Telegram alert when something *changes*: a gateway goes offline / comes
 * back, a cert crosses the "expiring soon" line, or the HQ control plane (signing service) drops. State
 * is kept in-memory so we alert on transitions, not every tick. Telegram creds reuse the watchdog's
 * env (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID); if unset, alerts are no-ops (logged once).
 */
object GatewayMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private const val INTERVAL_MS = 60_000L

    private val lastHealth = mutableMapOf<String, String>()  // gatewayId -> last health (online/degraded/offline)
    private val certWarned = mutableSetOf<String>()          // gateways already warned about cert expiry
    private val renewWarned = mutableSetOf<String>()         // gateways already warned about failing renews
    private var hqWasReachable = true

    fun start() {
        if (job != null) return
        // Nothing to watch unless an overlay is configured.
        if (!GatewayConfig.nebulaEnabled && GatewayConfig.activeScanSubnet == null) return
        AppLogger.info(AppLogger.Module.SYSTEM, "GATEWAY", "MONITOR_START", "interval=${INTERVAL_MS / 1000}s")
        job = scope.launch {
            while (isActive) {
                runCatching { tick() }
                    .onFailure { AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "MONITOR_ERROR", it.message ?: "") }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private suspend fun tick() {
        // HQ control plane reachability (Nebula only — ZeroTier/none has no /healthz).
        val hq = GatewayService.hqStatus()
        if (hq.configured && hq.overlay == "nebula") {
            if (!hq.reachable && hqWasReachable)
                GatewayAlerter.alert("HQ_DOWN", "⚠️ HQ control plane KHÔNG phản hồi", "overlay=${hq.overlay}")
            else if (hq.reachable && !hqWasReachable)
                GatewayAlerter.alert("HQ_UP", "✅ HQ control plane đã trở lại", "lighthouse=${hq.lighthouse ?: "?"}")
            hqWasReachable = hq.reachable
        }

        // Per-gateway transitions.
        val gws = GatewayService.listGateways()
        val liveIds = gws.map { it.id }.toSet()
        for (g in gws) {
            val prev = lastHealth[g.id]
            if (prev != null && prev != "offline" && g.health == "offline")
                GatewayAlerter.alert("GW_OFF_${g.id}", "🔴 Gateway offline: ${g.name}", "ip=${g.zerotierIp ?: "?"}")
            else if (prev == "offline" && g.health != "offline")
                GatewayAlerter.alert("GW_ON_${g.id}", "🟢 Gateway online: ${g.name}", "ip=${g.zerotierIp ?: "?"}")

            // Cert "expiring soon" — warn once per crossing; reset when a renewal pushes it back out.
            if (g.health == "degraded" && g.id !in certWarned) {
                GatewayAlerter.alert("CERT_${g.id}", "🟠 Cert sắp hết hạn: ${g.name}", "còn ${g.certDaysLeft} ngày")
                certWarned.add(g.id)
            }
            if ((g.certDaysLeft ?: Long.MAX_VALUE) > GatewayService.CERT_WARN_DAYS) certWarned.remove(g.id)

            // Cert renew failing silently (the NEBULA_SIGN_TOKEN-blank incident class): the agent retries
            // hourly, so renewFails >= 2 means HQ has been refusing/unreachable for 2+ hours straight.
            // Warn once per episode; the agent resets the counter to 0 on a successful renew.
            if (g.renewFails >= 2 && g.id !in renewWarned) {
                GatewayAlerter.alert("RENEW_${g.id}", "🟠 Renew cert đang FAIL: ${g.name}",
                    "${g.renewFails} lần liên tiếp, cert còn ${g.certDaysLeft ?: "?"} ngày — kiểm tra HQ signing service")
                renewWarned.add(g.id)
            }
            if (g.renewFails == 0 && g.id in renewWarned) {
                renewWarned.remove(g.id)
                GatewayAlerter.alert("RENEW_OK_${g.id}", "🟢 Renew cert đã thành công lại: ${g.name}",
                    "cert còn ${g.certDaysLeft ?: "?"} ngày")
            }

            lastHealth[g.id] = g.health
        }
        // Forget gateways that were deleted so stale state doesn't leak.
        lastHealth.keys.retainAll(liveIds)
        certWarned.retainAll(liveIds)
        renewWarned.retainAll(liveIds)
    }
}

/**
 * Minimal Telegram notifier for gateway alerts, reusing the watchdog's bot creds (TELEGRAM_BOT_TOKEN /
 * TELEGRAM_CHAT_ID). Per-key cooldown so a flapping gateway can't spam. Best-effort — never throws into
 * the monitor loop.
 */
object GatewayAlerter {
    private const val COOLDOWN_MS = 15 * 60 * 1000L
    private val lastSent = mutableMapOf<String, Long>()
    @Volatile private var warnedUnconfigured = false

    fun alert(key: String, message: String, details: String? = null) {
        val now = System.currentTimeMillis()
        synchronized(lastSent) {
            if (now - (lastSent[key] ?: 0L) < COOLDOWN_MS) return
            lastSent[key] = now
        }
        AppLogger.info(AppLogger.Module.SYSTEM, "GATEWAY", "ALERT", "$message ${details ?: ""}".trim())
        sendTelegram(message, details)
    }

    private fun sendTelegram(message: String, details: String?) {
        val token = System.getenv("TELEGRAM_BOT_TOKEN")?.trim().orEmpty()
        val chatId = System.getenv("TELEGRAM_CHAT_ID")?.trim().orEmpty()
        if (token.isBlank() || chatId.isBlank()) {
            if (!warnedUnconfigured) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "ALERT_NO_TELEGRAM", "TELEGRAM_BOT_TOKEN/CHAT_ID unset — gateway alerts only logged")
                warnedUnconfigured = true
            }
            return
        }
        val body = buildString {
            append(message)
            if (details != null) append("\n\n").append(details)
            append("\n\n🛰 Gateway")
        }
        runCatching {
            val conn = URL("https://api.telegram.org/bot$token/sendMessage").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = """{"chat_id":"$chatId","text":${jsonEscape(body)},"parse_mode":"HTML"}"""
            OutputStreamWriter(conn.outputStream).use { it.write(payload) }
            if (conn.responseCode != 200)
                AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "ALERT_TELEGRAM_FAIL", "http=${conn.responseCode}")
        }.onFailure { AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "ALERT_TELEGRAM_FAIL", it.message ?: "") }
    }

    private fun jsonEscape(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
}
