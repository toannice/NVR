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
import vn.aiview.nvr.plugins.hashString
import vn.aiview.nvr.services.EmailService
import io.ktor.server.sessions.*

@Serializable
data class LockRequest(val pattern: String)

@Serializable
data class LockStatusResponse(val isSetup: Boolean)

@Serializable
data class LockResponse(val success: Boolean, val error: String? = null)

@Serializable
data class OtpRequest(val email: String)

@Serializable
data class SetupRequest(val pattern: String, val email: String, val otp: String)

@Serializable
data class ResetRequest(val email: String)

@Serializable
data class ResetCompleteRequest(val pattern: String, val email: String, val otp: String)

private var failedAttempts = 0
private var lockUntil: Long = 0

fun Route.lockRouting() {
    route("/api/lock") {
        get("/status") {
            val count = transaction { LockConfig.selectAll().count() }
            call.respond(LockStatusResponse(count > 0))
        }

        post("/request-otp") {
            val data = call.receive<OtpRequest>()
            try {
                EmailService.sendOtp(data.email)
                call.respond(LockResponse(true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, LockResponse(false, "Không thể gửi email: ${e.message}"))
            }
        }

        post("/setup") {
            try {
                val data = call.receive<SetupRequest>()
                
                if (!EmailService.verifyOtp(data.email, data.otp)) {
                    call.respond(HttpStatusCode.BadRequest, LockResponse(false, "Mã xác thực không chính xác hoặc đã hết hạn"))
                    return@post
                }

                val patternHash = hashString(data.pattern)
                val gmailHash = hashString(data.email)

                transaction {
                    LockConfig.deleteAll()
                    LockConfig.insert {
                        it[hash] = patternHash
                        it[LockConfig.gmailHash] = gmailHash
                    }
                }
                
                failedAttempts = 0
                lockUntil = 0
                call.sessions.set(UserSession("admin"))
                call.respond(LockResponse(true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, LockResponse(false, "Lỗi thiết lập: ${e.message}"))
            }
        }

        post("/verify") {
            try {
                if (System.currentTimeMillis() < lockUntil) {
                    val remaining = (lockUntil - System.currentTimeMillis()) / 1000
                    call.respond(HttpStatusCode.TooManyRequests, LockResponse(false, "Đang bị khóa. Thử lại sau $remaining giây."))
                    return@post
                }

                val data = call.receive<LockRequest>()
                val existingHash = transaction {
                    LockConfig.selectAll().firstOrNull()?.get(LockConfig.hash)
                }
                
                val incomingHash = hashString(data.pattern)
                
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
                    call.respond(HttpStatusCode.Unauthorized, LockResponse(false, "Mã vẽ không đúng. Còn ${5 - failedAttempts} lần thử."))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, LockResponse(false, "Xác thực thất bại"))
            }
        }

        post("/reset-request") {
            val data = call.receive<ResetRequest>()
            val storedGmailHash = transaction {
                LockConfig.selectAll().firstOrNull()?.get(LockConfig.gmailHash)
            }

            if (storedGmailHash == null || storedGmailHash != hashString(data.email)) {
                call.respond(HttpStatusCode.NotFound, LockResponse(false, "Gmail không chính xác"))
                return@post
            }

            try {
                EmailService.sendOtp(data.email)
                call.respond(LockResponse(true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, LockResponse(false, "Lỗi gửi email: ${e.message}"))
            }
        }

        post("/reset-complete") {
            try {
                val data = call.receive<ResetCompleteRequest>()
                
                if (!EmailService.verifyOtp(data.email, data.otp)) {
                    call.respond(HttpStatusCode.BadRequest, LockResponse(false, "Mã xác thực không chính xác"))
                    return@post
                }

                val patternHash = hashString(data.pattern)
                
                transaction {
                    LockConfig.update({ LockConfig.id eq 1 }) {
                        it[hash] = patternHash
                    }
                }
                
                failedAttempts = 0
                lockUntil = 0
                call.sessions.set(UserSession("admin"))
                call.respond(LockResponse(true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, LockResponse(false, "Lỗi khôi phục: ${e.message}"))
            }
        }

        post("/logout") {
            call.sessions.clear<UserSession>()
            call.respond(LockResponse(true))
        }
    }
}
