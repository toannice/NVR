package vn.aiview.nvr.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import io.ktor.util.hex
import kotlinx.serialization.Serializable
import at.favre.lib.crypto.bcrypt.BCrypt
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable
data class UserSession(val id: String, val issuedAt: Long = 0L) : Principal

private val securityLog = LoggerFactory.getLogger("Security")

/**
 * HMAC key used to sign the NVR_SESSION cookie. Without this, the cookie is plaintext and anyone on
 * the LAN can forge `NVR_SESSION={"id":"admin"}` to get full admin access (bypassing the pattern lock).
 *
 * Priority: SESSION_SECRET env → persisted key file (data/session_secret) → freshly generated random
 * key (persisted so sessions survive restarts and each install has its own secret, never hardcoded).
 */
private fun sessionSignKey(): ByteArray {
    System.getenv("SESSION_SECRET")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.toByteArray() }

    val file = File(System.getenv("SESSION_SECRET_PATH") ?: "data/session_secret")
    if (file.exists()) {
        runCatching { hex(file.readText().trim()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
    }

    val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    runCatching {
        file.parentFile?.mkdirs()
        file.writeText(hex(key))
        file.setReadable(false, false); file.setReadable(true, true)   // owner-only
        file.setWritable(false, false); file.setWritable(true, true)
    }.onFailure { securityLog.warn("Could not persist session key (${it.message}); using in-memory key (sessions reset on restart).") }
    return key
}

fun Application.configureSecurity() {
    val sessionKey = sessionSignKey()
    install(Sessions) {
        cookie<UserSession>("NVR_SESSION") {
            cookie.path = "/"
            cookie.maxAgeInSeconds = 3600 * 24 // 1 day
            cookie.httpOnly = true
            // In a real production environment with HTTPS:
            // cookie.secure = true
            cookie.extensions["SameSite"] = "lax"
            // Sign the cookie (HMAC-SHA256). Stops LAN forgery of NVR_SESSION={"id":"admin"}.
            transform(SessionTransportTransformerMessageAuthentication(sessionKey))
        }
    }

    // Sliding session: re-issue the cookie (fresh 24h window) at most once per hour while
    // the UI is being used, so an always-open live-view tab never expires mid-session.
    // Only a machine idle for a full 24h straight falls back to the pattern lock.
    // Old cookies without issuedAt decode as 0 → refreshed on their next request.
    intercept(ApplicationCallPipeline.Plugins) {
        val session = call.sessions.get<UserSession>()
        if (session != null && System.currentTimeMillis() - session.issuedAt > 3600_000L) {
            call.sessions.set(session.copy(issuedAt = System.currentTimeMillis()))
        }
    }

    install(Authentication) {
        session<UserSession>("auth-session") {
            validate { session ->
                if (session.id.isNotEmpty()) session else null
            }
            challenge {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
            }
        }
    }
}

// SHA-256 with fixed salt — used for non-secret values (email lookup) and legacy migration
fun hashString(input: String): String {
    val salt = "NVR_SECRET_2026_AIVIEW"
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest((input + salt).toByteArray())
    return hashBytes.joinToString("") { "%02x".format(it) }
}

// bcrypt for pattern (secret). Cost 12 ≈ 300 ms — acceptable for a lock screen.
fun hashPattern(pattern: String): String = BCrypt.withDefaults().hashToString(12, pattern.toCharArray())

// Verify pattern against stored hash. Handles bcrypt (new) and SHA-256 (legacy migration).
// Returns (verified, needsRehash).
fun verifyPattern(input: String, stored: String): Pair<Boolean, Boolean> {
    return if (stored.startsWith("\$2")) {
        Pair(BCrypt.verifyer().verify(input.toCharArray(), stored).verified, false)
    } else {
        val legacyMatch = hashString(input) == stored
        Pair(legacyMatch, legacyMatch) // needsRehash=true when legacy matches
    }
}
