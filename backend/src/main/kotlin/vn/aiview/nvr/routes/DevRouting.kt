package vn.aiview.nvr.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.LockConfig
import vn.aiview.nvr.models.OtpTokens
import vn.aiview.nvr.plugins.hashString

@Serializable
data class InjectOtpRequest(val email: String, val code: String = "123456")

@Serializable
data class InjectOtpResponse(val success: Boolean, val code: String)

fun Route.devRouting() {
    route("/api/dev") {

        // Only active when E2E_TEST_MODE=true — returns 404 otherwise
        post("/inject-otp") {
            if (System.getenv("E2E_TEST_MODE") != "true") {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }

            val data = call.receive<InjectOtpRequest>()
            val expiry = System.currentTimeMillis() + 10 * 60 * 1000 // 10 min

            transaction {
                // Write OTP to otp_tokens for the test email
                OtpTokens.deleteWhere { OtpTokens.email eq data.email }
                OtpTokens.insert {
                    it[OtpTokens.email] = data.email
                    it[OtpTokens.code] = data.code
                    it[OtpTokens.expiry] = expiry
                }

                // Update gmail_hash in lock_config so reset-request accepts this email
                LockConfig.update {
                    it[LockConfig.gmailHash] = hashString(data.email)
                }
            }

            call.respond(InjectOtpResponse(true, data.code))
        }
    }
}
