package vn.aiview.nvr.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.LockConfig
import kotlinx.serialization.Serializable

import vn.aiview.nvr.plugins.UserSession
import vn.aiview.nvr.plugins.hashPattern
import io.ktor.server.sessions.*

@Serializable
data class LockRequest(val pattern: String)

@Serializable
data class LockResponse(val success: Boolean, val error: String? = null)

@Serializable
data class LockStatusResponse(val isSetup: Boolean)

private var failedAttempts = 0
private var lockUntil: Long = 0

fun Route.lockRouting() {
    route("/api/lock") {
        get("/status") {
            val count = transaction { LockConfig.selectAll().count() }
            call.respond(LockStatusResponse(count > 0))
        }

        post("/setup") {
            try {
                val data = call.receive<LockRequest>()
                val hashed = hashPattern(data.pattern)
                transaction {
                    LockConfig.deleteAll()
                    LockConfig.insert {
                        it[hash] = hashed
                    }
                }
                // Automatically log in after setup
                failedAttempts = 0
                lockUntil = 0
                call.sessions.set(UserSession("admin"))
                call.respond(LockResponse(true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, LockResponse(false, "Failed to set lock: ${e.message}"))
            }
        }

        post("/verify") {
            try {
                if (System.currentTimeMillis() < lockUntil) {
                    val remaining = (lockUntil - System.currentTimeMillis()) / 1000
                    call.respond(HttpStatusCode.TooManyRequests, LockResponse(false, "Locked out. Try again in $remaining seconds."))
                    return@post
                }

                val data = call.receive<LockRequest>()
                val existingHash = transaction {
                    LockConfig.selectAll().firstOrNull()?.get(LockConfig.hash)
                }
                
                val incomingHash = hashPattern(data.pattern)
                
                if (existingHash == incomingHash) {
                    failedAttempts = 0
                    lockUntil = 0
                    call.sessions.set(UserSession("admin"))
                    call.respond(LockResponse(true))
                } else {
                    failedAttempts++
                    if (failedAttempts >= 5) {
                        lockUntil = System.currentTimeMillis() + 60000 // Lock for 60 seconds
                    }
                    call.respond(HttpStatusCode.Unauthorized, LockResponse(false, "Invalid pattern. Attempt $failedAttempts of 5."))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, LockResponse(false, "Verification failed"))
            }
        }

        post("/logout") {
            call.sessions.clear<UserSession>()
            call.respond(mapOf("success" to true))
        }
    }
}
