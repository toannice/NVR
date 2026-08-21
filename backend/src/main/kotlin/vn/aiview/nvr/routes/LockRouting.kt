package vn.aiview.nvr.routes

import io.ktor.server.application.*
import io.ktor.server.auth.*
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
import vn.aiview.nvr.plugins.hashPattern
import vn.aiview.nvr.plugins.verifyPattern
import vn.aiview.nvr.services.RecoveryService
import vn.aiview.nvr.services.Throttle
import io.ktor.server.sessions.*

@Serializable
data class LockRequest(val pattern: String)

@Serializable
data class LockStatusResponse(
    val isSetup: Boolean,
    val hasRecoveryCode: Boolean = false,
    val hasQuestions: Boolean = false,
)

@Serializable
data class LockResponse(val success: Boolean, val error: String? = null)

@Serializable
data class SetupRequest(val pattern: String)

/** Returned whenever a fresh recovery code is minted — this is the only time it is ever readable. */
@Serializable
data class RecoveryCodeResponse(val success: Boolean, val recoveryCode: String? = null, val error: String? = null)

@Serializable
data class RecoveryCodeRequest(val code: String)

@Serializable
data class AnswersRequest(val answers: List<String>)

@Serializable
data class TicketResponse(val success: Boolean, val resetToken: String? = null, val error: String? = null)

@Serializable
data class ResetCompleteRequest(val resetToken: String, val pattern: String)

@Serializable
data class QuestionDto(val id: String, val text: String)

@Serializable
data class QuestionsResponse(val questions: List<QuestionDto>)

@Serializable
data class QuestionItem(val questionId: String, val answer: String)

@Serializable
data class SetQuestionsRequest(val pattern: String, val items: List<QuestionItem>)

@Serializable
data class PatternConfirmRequest(val pattern: String)

@Serializable
data class SecurityStatusResponse(
    val hasRecoveryCode: Boolean,
    val recoveryCreatedAt: Long?,
    val hasQuestions: Boolean,
    val questions: List<QuestionDto>,
)

// Escalating lock: 60s → 5m → 15m → 1h, then stays at 1h.
private val PATTERN_STEPS = listOf(60L, 300L, 900L, 3600L)
private val RECOVERY_STEPS = listOf(1800L)   // recovery code: 30 min
private val QUESTION_STEPS = listOf(3600L)   // questions have far less entropy: 1 hour

private const val MAX_ATTEMPTS = 5

private fun ApplicationCall.clientIp(): String = request.origin.remoteHost

private fun isSetup(): Boolean = transaction { LockConfig.selectAll().count() > 0 }

fun Route.lockRouting() {
    route("/api/lock") {
        get("/status") {
            val setup = isSetup()
            val (hasCode, _) = RecoveryService.status()
            call.respond(
                LockStatusResponse(
                    isSetup = setup,
                    hasRecoveryCode = setup && hasCode,
                    hasQuestions = setup && RecoveryService.configuredQuestions().isNotEmpty(),
                )
            )
        }

        // First-boot only. Guarded: once lock_config has a row this is a hard 409, otherwise
        // anyone on the LAN could re-run setup and take over the box.
        post("/setup") {
            try {
                if (isSetup()) {
                    AppLogger.warn(
                        AppLogger.Module.SECURITY, "AUTH", "SETUP_REJECTED",
                        "Setup attempted on an already-provisioned device | IP: ${call.clientIp()}"
                    )
                    call.respond(HttpStatusCode.Conflict, LockResponse(false, "Thiết bị đã được thiết lập"))
                    return@post
                }

                val data = call.receive<SetupRequest>()
                if (data.pattern.length < 4) {
                    call.respond(HttpStatusCode.BadRequest, LockResponse(false, "Mã vẽ quá ngắn"))
                    return@post
                }

                val patternHash = hashPattern(data.pattern)
                val code = RecoveryService.generateCode()
                val codeHash = hashPattern(code)
                val now = System.currentTimeMillis()

                transaction {
                    LockConfig.deleteAll()
                    LockConfig.insert {
                        it[hash] = patternHash
                        it[recoveryHash] = codeHash
                        it[recoveryCreatedAt] = now
                    }
                }

                Throttle.reset("pattern", call.clientIp())
                call.sessions.set(UserSession("admin", now))
                AppLogger.info(
                    AppLogger.Module.SECURITY, "AUTH", "CONFIG_CHANGE",
                    "Admin setup complete (pattern + recovery code) | IP: ${call.clientIp()}"
                )
                call.respond(RecoveryCodeResponse(true, code))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, LockResponse(false, "Lỗi thiết lập: ${e.message}"))
            }
        }

        post("/verify") {
            try {
                val ip = call.clientIp()
                val locked = Throttle.remainingLock("pattern", ip)
                if (locked > 0) {
                    call.respond(HttpStatusCode.TooManyRequests, LockResponse(false, "Đang bị khóa. Thử lại sau $locked giây."))
                    return@post
                }

                val data = call.receive<LockRequest>()
                val storedRow = transaction { LockConfig.selectAll().firstOrNull() }
                val existingHash = storedRow?.get(LockConfig.hash)

                val (matched, needsRehash) = if (existingHash != null) verifyPattern(data.pattern, existingHash) else Pair(false, false)

                if (matched) {
                    if (needsRehash) {
                        // Silently upgrade legacy SHA-256 hash to bcrypt
                        transaction { LockConfig.update { it[hash] = hashPattern(data.pattern) } }
                    }
                    Throttle.reset("pattern", ip)
                    call.sessions.set(UserSession("admin", System.currentTimeMillis()))
                    AppLogger.info(AppLogger.Module.SECURITY, "AUTH", "USER_LOGIN", "User: admin | IP: $ip | Status: Success")
                    call.respond(LockResponse(true))
                } else {
                    val left = Throttle.recordFailure("pattern", ip, MAX_ATTEMPTS, PATTERN_STEPS)
                    if (left == 0) {
                        val secs = Throttle.remainingLock("pattern", ip)
                        AppLogger.warn(AppLogger.Module.SECURITY, "AUTH", "INVALID_LOGIN", "IP: $ip | BLOCKED for ${secs}s")
                        call.respond(HttpStatusCode.TooManyRequests, LockResponse(false, "Sai quá nhiều lần. Khóa $secs giây."))
                    } else {
                        AppLogger.warn(AppLogger.Module.SECURITY, "AUTH", "INVALID_LOGIN", "IP: $ip | Attempts left: $left")
                        call.respond(HttpStatusCode.Unauthorized, LockResponse(false, "Mã vẽ không đúng. Còn $left lần thử."))
                    }
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, LockResponse(false, "Xác thực thất bại"))
            }
        }

        // ─── Offline recovery ────────────────────────────────────────────
        // Both paths only prove identity; they hand back a 5-minute ticket that
        // /reset-complete exchanges for the actual pattern change.

        post("/recovery/code") {
            val ip = call.clientIp()
            val locked = Throttle.remainingLock("recovery", ip)
            if (locked > 0) {
                call.respond(HttpStatusCode.TooManyRequests, TicketResponse(false, error = "Đang bị khóa. Thử lại sau $locked giây."))
                return@post
            }

            val data = call.receive<RecoveryCodeRequest>()
            when (RecoveryService.verifyCode(data.code)) {
                RecoveryService.CodeResult.OK -> {
                    Throttle.reset("recovery", ip)
                    AppLogger.warn(AppLogger.Module.SECURITY, "AUTH", "RECOVERY_CODE_OK", "Recovery code accepted | IP: $ip")
                    call.respond(TicketResponse(true, RecoveryService.issueTicket("recovery_code")))
                }
                RecoveryService.CodeResult.ALREADY_USED ->
                    call.respond(HttpStatusCode.BadRequest, TicketResponse(false, error = "Mã này đã được sử dụng. Hãy dùng cách khác hoặc liên hệ kỹ thuật."))
                RecoveryService.CodeResult.NOT_CONFIGURED ->
                    call.respond(HttpStatusCode.BadRequest, TicketResponse(false, error = "Thiết bị chưa có mã khôi phục."))
                RecoveryService.CodeResult.WRONG -> {
                    val left = Throttle.recordFailure("recovery", ip, MAX_ATTEMPTS, RECOVERY_STEPS)
                    AppLogger.warn(AppLogger.Module.SECURITY, "AUTH", "RECOVERY_CODE_FAIL", "IP: $ip | Attempts left: $left")
                    if (left == 0) {
                        val secs = Throttle.remainingLock("recovery", ip)
                        call.respond(HttpStatusCode.TooManyRequests, TicketResponse(false, error = "Sai quá nhiều lần. Khóa $secs giây."))
                    } else {
                        call.respond(HttpStatusCode.Unauthorized, TicketResponse(false, error = "Mã khôi phục không đúng. Còn $left lần thử."))
                    }
                }
            }
        }

        /** Public: the lock screen needs the question texts before the user is authenticated. */
        get("/recovery/questions") {
            call.respond(QuestionsResponse(RecoveryService.configuredQuestions().map { QuestionDto(it.id, it.text) }))
        }

        post("/recovery/questions") {
            val ip = call.clientIp()
            val locked = Throttle.remainingLock("questions", ip)
            if (locked > 0) {
                call.respond(HttpStatusCode.TooManyRequests, TicketResponse(false, error = "Đang bị khóa. Thử lại sau $locked giây."))
                return@post
            }

            val data = call.receive<AnswersRequest>()
            if (RecoveryService.configuredQuestions().isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, TicketResponse(false, error = "Thiết bị chưa đặt câu hỏi bảo mật."))
                return@post
            }

            if (RecoveryService.verifyAnswers(data.answers)) {
                Throttle.reset("questions", ip)
                AppLogger.warn(AppLogger.Module.SECURITY, "AUTH", "RECOVERY_QA_OK", "Security answers accepted | IP: $ip")
                call.respond(TicketResponse(true, RecoveryService.issueTicket("questions")))
            } else {
                val left = Throttle.recordFailure("questions", ip, MAX_ATTEMPTS, QUESTION_STEPS)
                AppLogger.warn(AppLogger.Module.SECURITY, "AUTH", "RECOVERY_QA_FAIL", "IP: $ip | Attempts left: $left")
                if (left == 0) {
                    val secs = Throttle.remainingLock("questions", ip)
                    call.respond(HttpStatusCode.TooManyRequests, TicketResponse(false, error = "Sai quá nhiều lần. Khóa $secs giây."))
                } else {
                    // Never say which answer was wrong.
                    call.respond(HttpStatusCode.Unauthorized, TicketResponse(false, error = "Câu trả lời không chính xác. Còn $left lần thử."))
                }
            }
        }

        post("/reset-complete") {
            try {
                val data = call.receive<ResetCompleteRequest>()
                val method = RecoveryService.consumeTicket(data.resetToken)
                if (method == null) {
                    call.respond(HttpStatusCode.Forbidden, RecoveryCodeResponse(false, error = "Phiên khôi phục đã hết hạn. Hãy xác minh lại."))
                    return@post
                }
                if (data.pattern.length < 4) {
                    call.respond(HttpStatusCode.BadRequest, RecoveryCodeResponse(false, error = "Mã vẽ quá ngắn"))
                    return@post
                }

                val patternHash = hashPattern(data.pattern)
                // The old recovery code dies with this reset (single use) — mint a new one so the
                // user is never left without an offline way back in.
                val newCode = RecoveryService.generateCode()
                val newCodeHash = hashPattern(newCode)
                val now = System.currentTimeMillis()

                transaction {
                    LockConfig.update {
                        it[hash] = patternHash
                        it[recoveryHash] = newCodeHash
                        it[recoveryCreatedAt] = now
                        it[recoveryUsedAt] = null
                    }
                }

                val ip = call.clientIp()
                Throttle.reset("pattern", ip)
                Throttle.reset("recovery", ip)
                Throttle.reset("questions", ip)
                call.sessions.set(UserSession("admin", now))
                AppLogger.warn(
                    AppLogger.Module.SECURITY, "AUTH", "PATTERN_RESET",
                    "Pattern reset via $method | IP: $ip"
                )
                call.respond(RecoveryCodeResponse(true, newCode))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, RecoveryCodeResponse(false, error = "Lỗi khôi phục: ${e.message}"))
            }
        }

        post("/logout") {
            call.sessions.clear<UserSession>()
            call.respond(LockResponse(true))
        }

        // ─── Authenticated management (Cài đặt → Bảo mật) ────────────────
        // Every mutation re-asks for the current pattern: a session hijacked from an
        // unattended browser must not be enough to mint a new recovery code.
        authenticate("auth-session") {

            get("/security-status") {
                val (hasCode, createdAt) = RecoveryService.status()
                val qs = RecoveryService.configuredQuestions()
                call.respond(
                    SecurityStatusResponse(
                        hasRecoveryCode = hasCode,
                        recoveryCreatedAt = createdAt,
                        hasQuestions = qs.isNotEmpty(),
                        questions = qs.map { QuestionDto(it.id, it.text) },
                    )
                )
            }

            get("/questions/catalog") {
                call.respond(QuestionsResponse(RecoveryService.CATALOG.map { QuestionDto(it.id, it.text) }))
            }

            post("/recovery/regenerate") {
                val data = call.receive<PatternConfirmRequest>()
                if (!confirmPattern(data.pattern)) {
                    call.respond(HttpStatusCode.Unauthorized, RecoveryCodeResponse(false, error = "Mã vẽ không đúng"))
                    return@post
                }
                val code = RecoveryService.generateCode()
                RecoveryService.persistCode(code)
                AppLogger.warn(
                    AppLogger.Module.SECURITY, "AUTH", "RECOVERY_CODE_REGENERATED",
                    "New recovery code issued | IP: ${call.clientIp()}"
                )
                call.respond(RecoveryCodeResponse(true, code))
            }

            post("/questions/set") {
                val data = call.receive<SetQuestionsRequest>()
                if (!confirmPattern(data.pattern)) {
                    call.respond(HttpStatusCode.Unauthorized, LockResponse(false, "Mã vẽ không đúng"))
                    return@post
                }
                val err = RecoveryService.setQuestions(data.items.map { it.questionId to it.answer })
                if (err != null) {
                    call.respond(HttpStatusCode.BadRequest, LockResponse(false, err))
                    return@post
                }
                AppLogger.info(
                    AppLogger.Module.SECURITY, "AUTH", "CONFIG_CHANGE",
                    "Security questions updated | IP: ${call.clientIp()}"
                )
                call.respond(LockResponse(true))
            }

            post("/questions/clear") {
                val data = call.receive<PatternConfirmRequest>()
                if (!confirmPattern(data.pattern)) {
                    call.respond(HttpStatusCode.Unauthorized, LockResponse(false, "Mã vẽ không đúng"))
                    return@post
                }
                RecoveryService.clearQuestions()
                AppLogger.warn(
                    AppLogger.Module.SECURITY, "AUTH", "CONFIG_CHANGE",
                    "Security questions cleared | IP: ${call.clientIp()}"
                )
                call.respond(LockResponse(true))
            }
        }
    }
}

private fun confirmPattern(pattern: String): Boolean {
    val stored = transaction { LockConfig.selectAll().firstOrNull()?.get(LockConfig.hash) } ?: return false
    val (matched, _) = verifyPattern(pattern, stored)
    return matched
}
