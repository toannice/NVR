package vn.aiview.nvr.services

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import vn.aiview.nvr.models.IntegrationSettings
import vn.aiview.nvr.plugins.hashPattern
import vn.aiview.nvr.plugins.verifyPattern
import java.util.concurrent.ConcurrentHashMap

/**
 * Backing logic for the AiView Box integration (NVR_AIView_Integration_Protocol v1.1).
 * Owns: integration settings (enable + credentials), Box auth check, restream URL building,
 * and a simple per-IP rate limiter. Fully independent of pattern-lock / cloud config.
 */
object IntegrationService {

    data class Settings(val enabled: Boolean, val username: String, val passwordHash: String)

    /** Read the single-row settings (id=1). Returns disabled defaults if the row is somehow missing. */
    fun readSettings(): Settings = transaction {
        IntegrationSettings.selectAll().where { IntegrationSettings.id eq 1 }.firstOrNull()?.let {
            Settings(
                enabled = it[IntegrationSettings.enabled],
                username = it[IntegrationSettings.username],
                passwordHash = it[IntegrationSettings.passwordHash],
            )
        } ?: Settings(enabled = false, username = "", passwordHash = "")
    }

    /**
     * Update settings. `password` null/blank → keep existing hash (so the admin can toggle/rename
     * without retyping). A non-blank password is bcrypt-hashed via the shared hashPattern().
     */
    fun updateSettings(enabled: Boolean, username: String, password: String?) = transaction {
        val exists = IntegrationSettings.selectAll().where { IntegrationSettings.id eq 1 }.firstOrNull() != null
        val newHash = password?.takeIf { it.isNotBlank() }?.let { hashPattern(it) }
        if (exists) {
            IntegrationSettings.update({ IntegrationSettings.id eq 1 }) {
                it[IntegrationSettings.enabled] = enabled
                it[IntegrationSettings.username] = username
                if (newHash != null) it[IntegrationSettings.passwordHash] = newHash
                it[IntegrationSettings.updatedAt] = CurrentDateTime
            }
        } else {
            IntegrationSettings.insert {
                it[IntegrationSettings.id] = 1
                it[IntegrationSettings.enabled] = enabled
                it[IntegrationSettings.username] = username
                it[IntegrationSettings.passwordHash] = newHash ?: ""
            }
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

    /** v1.1 default: restream is exposed inside the trusted Box↔NVR LAN without RTSP auth. */
    const val RTSP_AUTH = false
    const val MAX_CAMERAS = 64
    const val PROTOCOL_VERSION = "1.1"

    private fun detectLanIp(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces()
            .toList().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filterNot { val n = it.name.lowercase(); n.startsWith("docker") || n.startsWith("br-") || n.startsWith("veth") }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .map { it.hostAddress }
            .firstOrNull { ip -> ip.startsWith("192.168.") || ip.startsWith("10.") || ip.matches(Regex("^172\\.(1[6-9]|2\\d|3[01])\\..*")) }
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
