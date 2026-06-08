package vn.aiview.nvr.gatewayonboard.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Discovers edge gateways by probing every host in the phone's local /24 on the agent port.
 * No mDNS/WS-Discovery dependency — a flat HTTP sweep is robust across field Wi-Fi setups.
 */
class LanScanner(private val agent: AgentClient) {

    /** The phone's own site-local IPv4 (e.g. 192.168.2.107), or null if not on a private LAN. */
    fun localIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Scans the local /24 for gateways. [onFound] is invoked (off the main thread) as each one
     * answers so the UI can populate incrementally; the full sorted list is also returned.
     */
    suspend fun scan(
        port: Int = AgentClient.DEFAULT_PORT,
        onFound: suspend (Gateway) -> Unit = {},
    ): List<Gateway> = coroutineScope {
        val self = localIpv4() ?: return@coroutineScope emptyList()
        val octets = self.split(".")
        if (octets.size != 4) return@coroutineScope emptyList()
        val base = "${octets[0]}.${octets[1]}.${octets[2]}"

        val gate = Semaphore(MAX_PARALLEL)
        val found = java.util.Collections.synchronizedList(mutableListOf<Gateway>())

        (1..254).map { last ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val host = "$base.$last"
                    val id = agent.identity(host, port) ?: return@withPermit
                    val gw = Gateway(host, id)
                    found.add(gw)
                    onFound(gw)
                }
            }
        }.awaitAll()

        found.sortedBy { gw -> gw.host.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    private companion object {
        const val MAX_PARALLEL = 48
    }
}
