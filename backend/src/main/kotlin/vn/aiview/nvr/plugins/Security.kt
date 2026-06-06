package vn.aiview.nvr.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
import at.favre.lib.crypto.bcrypt.BCrypt
import java.security.MessageDigest

@Serializable
data class UserSession(val id: String) : Principal

fun Application.configureSecurity() {
    install(Sessions) {
        cookie<UserSession>("NVR_SESSION") {
            cookie.path = "/"
            cookie.maxAgeInSeconds = 3600 * 24 // 1 day
            cookie.httpOnly = true
            // In a real production environment with HTTPS:
            // cookie.secure = true 
            cookie.extensions["SameSite"] = "lax"
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
