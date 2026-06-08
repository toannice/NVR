package vn.aiview.nvr.gateway

import java.io.File

/**
 * NVR-side gateway settings, all from env / Docker secret (no DB):
 *   - EDGE_AGENT_PORT      agent HTTP port (default 9797)
 *   - ZT_SCAN_SUBNET       ZeroTier subnet to auto-scan for gateways, e.g. "10.34.24.0/24"
 *   - fleet token          /run/secrets/edge_agent_token, else env EDGE_AGENT_TOKEN
 * The same fleet token must be configured on every Pi (agent.env) and every NVR.
 */
object GatewayConfig {
    val agentPort: Int get() = System.getenv("EDGE_AGENT_PORT")?.toIntOrNull() ?: 9797
    val scanSubnet: String? get() = System.getenv("ZT_SCAN_SUBNET")?.takeIf { it.isNotBlank() }
    val scanIntervalSec: Long get() = System.getenv("GATEWAY_SCAN_INTERVAL_SEC")?.toLongOrNull() ?: 30L

    val token: String
        get() {
            val secret = File("/run/secrets/edge_agent_token")
            if (secret.exists()) return secret.readText().trim()
            return System.getenv("EDGE_AGENT_TOKEN") ?: ""
        }

    // ── Self-hosted ZeroTier controller (lets the NVR authorize new members itself, no ztncui) ──
    //   - ZT_CONTROLLER_URL    base URL of the controller API, e.g. "http://192.168.2.106:9993"
    //   - ZT_NETWORK_ID        fleet network id (16-hex), same one the gateways join
    //   - controller token     /run/secrets/zt_controller_token, else env ZT_CONTROLLER_TOKEN
    //                          (= contents of the controller's /var/lib/zerotier-one/authtoken.secret)
    val controllerUrl: String? get() = System.getenv("ZT_CONTROLLER_URL")?.takeIf { it.isNotBlank() }
    val controllerNetworkId: String? get() = System.getenv("ZT_NETWORK_ID")?.takeIf { it.isNotBlank() }

    val controllerToken: String
        get() {
            val secret = File("/run/secrets/zt_controller_token")
            if (secret.exists()) return secret.readText().trim()
            return System.getenv("ZT_CONTROLLER_TOKEN") ?: ""
        }
}
