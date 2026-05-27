package vn.aiview.nvr.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.*
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.LockConfig
import kotlinx.serialization.Serializable
import vn.aiview.nvr.utils.AppLogger

import vn.aiview.nvr.plugins.UserSession
import vn.aiview.nvr.plugins.hashString
import vn.aiview.nvr.plugins.hashPattern
import vn.aiview.nvr.plugins.verifyPattern
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
data class VerifyOtpRequest(val email: String, val otp: String)

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
                    val ip = call.request.origin.remoteHost
                    AppLogger.warn(AppLogger.Module.SECURITY, "AUTH", "SETUP_FAILED", "Invalid OTP for email: ${data.email} | IP: $ip")
                    call.respond(HttpStatusCode.BadRequest, LockResponse(false, "Mã xác thực không chính xác hoặc đã hết hạn"))
                    return@post
                }

                val patternHash = hashPattern(data.pattern)
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
                val ip = call.request.origin.remoteHost
                AppLogger.info(AppLogger.Module.SECURITY, "AUTH", "CONFIG_CHANGE", "Admin setup complete (Pattern & Email) | IP: $ip")
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
                val storedRow = transaction {
                    LockConfig.selectAll().firstOrNull()
                }
                val existingHash = storedRow?.get(LockConfig.hash)

                val (matched, needsRehash) = if (existingHash != null) verifyPattern(data.pattern, existingHash) else Pair(false, false)

                if (matched) {
                    if (needsRehash) {
                        // Silently upgrade legacy SHA-256 hash to bcrypt
                        transaction { LockConfig.update { it[hash] = hashPattern(data.pattern) } }
                    }
                    failedAttempts = 0
                    lockUntil = 0
                    call.sessions.set(UserSession("admin"))
                    val ip = call.request.origin.remoteHost
                    AppLogger.info(AppLogger.Module.SECURITY, "AUTH", "USER_LOGIN", "User: admin | IP: $ip | Status: Success")
                    call.respond(LockResponse(true))
                } else {
                    failedAttempts++
                    val ip = call.request.origin.remoteHost
                    if (failedAttempts >= 5) {
                        lockUntil = System.currentTimeMillis() + 60000 // Lock for 60 seconds
                        AppLogger.warn(AppLogger.Module.SECURITY, "AUTH", "INVALID_LOGIN", "IP: $ip | Attempts: $failedAttempts | BLOCKED for 60s")
                    } else {
                        AppLogger.warn(AppLogger.Module.SECURITY, "AUTH", "INVALID_LOGIN", "IP: $ip | Attempts: $failedAttempts")
                    }
                    call.respond(HttpStatusCode.Unauthorized, LockResponse(false, "Mã vẽ không đúng. Còn ${5 - failedAttempts} lần thử."))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, LockResponse(false, "Xác thực thất bại"))
            }
        }

        post("/verify-otp") {
            val data = call.receive<VerifyOtpRequest>()
            if (EmailService.peekOtp(data.email, data.otp)) {
                call.respond(LockResponse(true))
            } else {
                call.respond(HttpStatusCode.BadRequest, LockResponse(false, "Mã không đúng hoặc đã hết hạn"))
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

                val patternHash = hashPattern(data.pattern)

                transaction {
                    LockConfig.update {
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
