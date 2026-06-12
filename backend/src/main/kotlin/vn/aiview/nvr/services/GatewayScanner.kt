package vn.aiview.nvr.services

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import vn.aiview.nvr.gateway.EdgeAgentClient
import vn.aiview.nvr.gateway.GatewayConfig
import vn.aiview.nvr.utils.AppLogger
import java.net.InetAddress

/**
 * Auto-discovers edge gateways by sweeping the overlay subnet and calling each host's /health
 * (bounded concurrency, short timeout). The subnet is the active overlay's range: NEBULA_OVERLAY_SUBNET
 * (Nebula — the locked production overlay) or, when Nebula is unconfigured, the legacy ZT_SCAN_SUBNET
 * (ZeroTier fallback, dormant). This is the "auto connect" piece — the gateway never registers itself;
 * the NVR finds it. Found gateways land as `discovered` for the admin to approve. Disabled when no
 * overlay subnet is set.
 */
object GatewayScanner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private const val MAX_CONCURRENCY = 32

    fun start() {
        val subnet = GatewayConfig.activeScanSubnet
        if (subnet == null) {
            AppLogger.info(AppLogger.Module.SYSTEM, "GATEWAY", "SCAN_DISABLED", "no scan subnet (ZT_SCAN_SUBNET / NEBULA_OVERLAY_SUBNET)")
            return
        }
        if (job != null) return
        AppLogger.info(AppLogger.Module.SYSTEM, "GATEWAY", "SCAN_START", "subnet=$subnet interval=${GatewayConfig.scanIntervalSec}s")
        job = scope.launch {
            while (isActive) {
                runCatching { scanNow() }
                    .onFailure { AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "SCAN_ERROR", it.message ?: "") }
                delay(GatewayConfig.scanIntervalSec * 1000)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** One sweep of the subnet. Safe to call manually (POST /api/gateways/scan). */
    suspend fun scanNow(): Int = coroutineScope {
        val subnet = GatewayConfig.activeScanSubnet ?: return@coroutineScope 0
        val token = GatewayConfig.token
        val port = GatewayConfig.agentPort
        val hosts = expandCidr(subnet)
        val sem = Semaphore(MAX_CONCURRENCY)
        var found = 0
        val jobs = hosts.map { ip ->
            async {
                sem.withPermit {
                    val h = EdgeAgentClient.health(ip, port, token, timeoutMs = 1_500, quiet = true)
                    if (h != null && h.status == "ok") {
                        GatewayService.upsertFromHealth(ip, h)
                        synchronized(this@GatewayScanner) { found++ }
                    }
                }
            }
        }
        jobs.awaitAll()
        AppLogger.info(AppLogger.Module.SYSTEM, "GATEWAY", "SCAN_DONE", "subnet=$subnet hosts=${hosts.size} found=$found")
        found
    }

    /** Expand an IPv4 CIDR into host addresses. Capped at /20 (4094 hosts) to bound the sweep. */
    fun expandCidr(cidr: String): List<String> {
        return try {
            val (base, prefixStr) = cidr.split("/")
            val prefix = prefixStr.toInt()
            if (prefix < 20 || prefix > 32) return emptyList()
            val baseInt = InetAddress.getByName(base).address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
            val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
            val network = baseInt and mask
            val size = 1L shl (32 - prefix)
            val out = ArrayList<String>()
            // skip network (.0) and broadcast (last) addresses
            for (i in 1 until (size - 1)) {
                val ipInt = network + i.toInt()
                out.add("${(ipInt ushr 24) and 0xff}.${(ipInt ushr 16) and 0xff}.${(ipInt ushr 8) and 0xff}.${ipInt and 0xff}")
            }
            out
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "CIDR_PARSE_FAIL", "$cidr: ${e.message}")
            emptyList()
        }
    }
}
