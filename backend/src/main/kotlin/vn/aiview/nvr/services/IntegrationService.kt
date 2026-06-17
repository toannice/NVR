package vn.aiview.nvr.services

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import vn.aiview.nvr.models.IntegrationConnections
import vn.aiview.nvr.models.IntegrationSettings
import vn.aiview.nvr.plugins.hashPattern
import vn.aiview.nvr.plugins.verifyPattern
import java.util.concurrent.ConcurrentHashMap

/**
 * Backing logic for the AiView Box integration (NVR_AIView_Integration_Protocol v1.2).
 * Owns: integration settings (enable + credentials), Box auth check, restream URL building,
 * and a simple per-IP rate limiter. Fully independent of pattern-lock / cloud config.
 */
object IntegrationService {

    data class Settings(
        val enabled: Boolean,
        val username: String,
        val passwordHash: String,
        val passwordLen: Int,
        val boxIp: String,
        val boxPort: Int,
    )

    /** Read the single-row settings (id=1). Returns disabled defaults if the row is somehow missing. */
    fun readSettings(): Settings = transaction {
        IntegrationSettings.selectAll().where { IntegrationSettings.id eq 1 }.firstOrNull()?.let {
            Settings(
                enabled = it[IntegrationSettings.enabled],
                username = it[IntegrationSettings.username],
                passwordHash = it[IntegrationSettings.passwordHash],
                passwordLen = it[IntegrationSettings.passwordLen],
                boxIp = it[IntegrationSettings.boxIp],
                boxPort = it[IntegrationSettings.boxPort],
            )
        } ?: Settings(enabled = false, username = "", passwordHash = "", passwordLen = 0, boxIp = "", boxPort = 8088)
    }

    /**
     * Update settings. `password` null/blank → keep existing hash (so the admin can toggle/rename
     * without retyping). A non-blank password is bcrypt-hashed via the shared hashPattern();
     * its plaintext length is stored separately to drive the masked •••• UI hint.
     * After persisting, restarts the Box WS client if boxIp changed or integration toggled.
     */
    fun updateSettings(enabled: Boolean, username: String, password: String?, boxIp: String = "", boxPort: Int = 8088) = transaction {
        val exists = IntegrationSettings.selectAll().where { IntegrationSettings.id eq 1 }.firstOrNull() != null
        val newPassword = password?.takeIf { it.isNotBlank() }
        val newHash = newPassword?.let { hashPattern(it) }
        if (exists) {
            IntegrationSettings.update({ IntegrationSettings.id eq 1 }) {
                it[IntegrationSettings.enabled] = enabled
                it[IntegrationSettings.username] = username
                if (newHash != null) {
                    it[IntegrationSettings.passwordHash] = newHash
                    it[IntegrationSettings.passwordLen] = newPassword.length
                }
                it[IntegrationSettings.boxIp] = boxIp
                it[IntegrationSettings.boxPort] = boxPort
                it[IntegrationSettings.updatedAt] = CurrentDateTime
            }
        } else {
            IntegrationSettings.insert {
                it[IntegrationSettings.id] = 1
                it[IntegrationSettings.enabled] = enabled
                it[IntegrationSettings.username] = username
                it[IntegrationSettings.passwordHash] = newHash ?: ""
                it[IntegrationSettings.passwordLen] = newPassword?.length ?: 0
                it[IntegrationSettings.boxIp] = boxIp
                it[IntegrationSettings.boxPort] = boxPort
            }
        }
    }

    /** Start or stop the Box WS client based on current settings. Call after updateSettings or on boot. */
    fun applyBoxClient() {
        val s = readSettings()
        if (s.enabled && s.boxIp.isNotBlank()) {
            BoxEventClient.start(s.boxIp, s.boxPort)
        } else {
            BoxEventClient.stop()
        }
    }

    sealed class AuthResult {
        object Ok : AuthResult()
        object Disabled : AuthResult()
        object Invalid : AuthResult()
    }

    /** Verify the integration credential pair (v1.1 §8 hướng A). */
    fun verifyBoxAuth(username: String, password: String): AuthResult {
        val s = readSettings()
        if (!s.enabled) return AuthResult.Disabled
        if (s.username.isBlank() || s.passwordHash.isBlank()) return AuthResult.Invalid
        val (matched, _) = verifyPattern(password, s.passwordHash)
        return if (username == s.username && matched) AuthResult.Ok else AuthResult.Invalid
    }

    // ---- Connection tracking: which Box IPs have actually authenticated (admin visibility) ----

    data class Connection(
        val ip: String,
        val username: String,
        val firstSeen: Long,
        val lastSeen: Long,
        val hitCount: Long,
        val lastEndpoint: String,
    )

    /**
     * Record a *successful authenticated* call from a Box. Upserts the per-IP row, bumping the
     * activity counter and timestamps and refreshing the username it logged in as.
     */
    fun recordConnection(ip: String, username: String, endpoint: String) = transaction {
        val now = System.currentTimeMillis()
        val existing = IntegrationConnections.selectAll().where { IntegrationConnections.ip eq ip }.firstOrNull()
        if (existing != null) {
            IntegrationConnections.update({ IntegrationConnections.ip eq ip }) {
                it[IntegrationConnections.username] = username
                it[IntegrationConnections.lastSeen] = now
                it[IntegrationConnections.hitCount] = existing[IntegrationConnections.hitCount] + 1
                it[IntegrationConnections.lastEndpoint] = endpoint
            }
        } else {
            IntegrationConnections.insert {
                it[IntegrationConnections.ip] = ip
                it[IntegrationConnections.username] = username
                it[IntegrationConnections.firstSeen] = now
                it[IntegrationConnections.lastSeen] = now
                it[IntegrationConnections.hitCount] = 1
                it[IntegrationConnections.lastEndpoint] = endpoint
            }
        }
    }

    /**
     * Heartbeat from an *unauthenticated* poll (e.g. /getcamerastatus): only bumps lastSeen for an
     * IP that has already authenticated at least once — never creates a row from an anonymous call.
     */
    fun touchConnection(ip: String, endpoint: String) = transaction {
        val existing = IntegrationConnections.selectAll().where { IntegrationConnections.ip eq ip }.firstOrNull()
            ?: return@transaction
        IntegrationConnections.update({ IntegrationConnections.ip eq ip }) {
            it[IntegrationConnections.lastSeen] = System.currentTimeMillis()
            it[IntegrationConnections.hitCount] = existing[IntegrationConnections.hitCount] + 1
            it[IntegrationConnections.lastEndpoint] = endpoint
        }
    }

    /** All known Box connections, most recently active first. */
    fun listConnections(): List<Connection> = transaction {
        IntegrationConnections.selectAll().map {
            Connection(
                ip = it[IntegrationConnections.ip],
                username = it[IntegrationConnections.username],
                firstSeen = it[IntegrationConnections.firstSeen],
                lastSeen = it[IntegrationConnections.lastSeen],
                hitCount = it[IntegrationConnections.hitCount],
                lastEndpoint = it[IntegrationConnections.lastEndpoint],
            )
        }.sortedByDescending { it.lastSeen }
    }

    // ---- Restream URL building (v1.1 §3.2, A6) ----

    /** LAN IP that the Box can reach for the go2rtc restream (port 8554). */
    fun lanIp(): String =
        System.getenv("FRIGATE_HOST_IP")?.takeIf { it.isNotBlank() } ?: detectLanIp()

    /**
     * Restream RTSP URL for a channel. v1.1 default rtsp_auth=false → bare URL.
     * (When commercial RTSP auth is enabled later, embed credentials here and set rtsp_auth=true.)
     */
    fun streamUrl(channelId: String, sub: Boolean): String {
        val name = if (sub) "${channelId}_sub" else "${channelId}_main"
        return "rtsp://${lanIp()}:8554/$name"
    }

    /** v1.2 default: restream is exposed inside the trusted Box↔NVR LAN without RTSP auth. */
    const val RTSP_AUTH = false
    const val MAX_CAMERAS = 64
    const val PROTOCOL_VERSION = "1.2"

    /**
     * Quick liveness check for Frigate (§4.1 — 503 SERVICE_UNAVAILABLE).
     * Returns false if Frigate is restarting or unreachable.
     */
    fun isFrigateReady(): Boolean = try {
        val conn = java.net.URL("http://127.0.0.1:5000/api/version").openConnection()
            as java.net.HttpURLConnection
        conn.connectTimeout = 2_000
        conn.readTimeout    = 2_000
        conn.requestMethod  = "GET"
        conn.responseCode in 200..299
    } catch (_: Exception) { false }

    private val privateIp172 = Regex("^172\\.(1[6-9]|2\\d|3[01])\\..*")

    private fun detectLanIp(): String = try {
        val candidates = java.net.NetworkInterface.getNetworkInterfaces()
            .toList().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            // Skip docker/bridge AND overlay/VPN interfaces (Nebula, ZeroTier, WireGuard,
            // Tailscale, tun) — their IPs are NOT reachable by a LAN Box pulling the restream.
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
        // Prefer real-LAN ranges (192.168 / 172.16-31) over ambiguous 10.x (often VPN/overlay).
        candidates.firstOrNull { it.startsWith("192.168.") || it.matches(privateIp172) }
            ?: candidates.firstOrNull()
            ?: "127.0.0.1"
    } catch (_: Exception) { "127.0.0.1" }

    /**
     * In-memory sliding-window rate limiter keyed by client IP (v1.1 §4.4).
     * @return null if allowed, else the Retry-After value in seconds.
     */
    object RateLimiter {
        private const val MAX = 30            // requests
        private const val WINDOW_MS = 60_000L // per minute
        private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

        fun check(ip: String): Long? {
            val now = System.currentTimeMillis()
            val dq = hits.getOrPut(ip) { ArrayDeque() }
            synchronized(dq) {
                while (dq.isNotEmpty() && now - dq.first() >= WINDOW_MS) dq.removeFirst()
                if (dq.size >= MAX) {
                    val retryMs = WINDOW_MS - (now - dq.first())
                    return ((retryMs / 1000) + 1).coerceAtLeast(1)
                }
                dq.addLast(now)
                return null
            }
        }
    }
}
