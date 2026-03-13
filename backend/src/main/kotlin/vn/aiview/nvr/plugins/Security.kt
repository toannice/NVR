package vn.aiview.nvr.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable
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

/**
 * Basic SHA-256 hashing for pattern strings.
 * In a high-security environment, BCrypt/Argon2 would be preferred.
 */
fun hashPattern(pattern: String): String {
    val salt = "NVR_SECRET_2026_AIVIEW" // Salt should ideally be unique per device
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest((pattern + salt).toByteArray())
    return hashBytes.joinToString("") { "%02x".format(it) }
}
