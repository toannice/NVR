package vn.aiview.nvr.utils

/**
 * One place that answers "which IP of this box can a LAN client actually reach?".
 *
 * Used for go2rtc WebRTC candidates, the Box restream URLs and the ONVIF server. Getting it
 * wrong is not subtle: publishing an overlay address as a WebRTC candidate makes live view
 * black for every LAN browser, because nothing on the LAN can route to 10.10.20.x.
 */
object NetUtils {

    private val privateIp172 = Regex("^172\\.(1[6-9]|2\\d|3[01])\\..*")

    /** FRIGATE_HOST_IP wins when set — it is what docker-compose binds ports to. */
    fun lanIp(): String =
        System.getenv("FRIGATE_HOST_IP")?.takeIf { it.isNotBlank() } ?: detectLanIp()

    /**
     * Best-effort physical LAN address.
     *
     * Skips docker bridges AND every overlay/VPN interface (Nebula, ZeroTier, WireGuard,
     * Tailscale, tun) — those carry 10.x addresses that look private but are unreachable
     * from the local network. Among what is left, a 192.168/172.16-31 address is preferred
     * over a bare 10.x for the same reason.
     */
    fun detectLanIp(): String = try {
        val candidates = java.net.NetworkInterface.getNetworkInterfaces()
            .toList().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filterNot {
                val n = it.name.lowercase()
                n.startsWith("docker") || n.startsWith("br-") || n.startsWith("veth") ||
                    n.startsWith("nebula") || n.startsWith("zt") || n.startsWith("tun") ||
                    n.startsWith("tailscale") || n.startsWith("wg") || n.startsWith("utun")
            }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .map { it.hostAddress }
            .filter { ip -> ip.startsWith("192.168.") || ip.startsWith("10.") || ip.matches(privateIp172) }
            .toList()

        candidates.firstOrNull { it.startsWith("192.168.") || it.matches(privateIp172) }
            ?: candidates.firstOrNull()
            ?: "127.0.0.1"
    } catch (_: Exception) {
        "127.0.0.1"
    }
}
