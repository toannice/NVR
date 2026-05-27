package vn.aiview.nvr.services

import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.OtpTokens
import java.io.File
import java.util.Properties

object EmailService {
    // Reads from Docker secret file first; falls back to env var for local dev
    private fun readSecret(secretName: String, envFallback: String): String {
        val secretFile = File("/run/secrets/$secretName")
        if (secretFile.exists()) return secretFile.readText().trim()
        return System.getenv(envFallback) ?: ""
    }

    private fun getSession(): Session {
        val props = Properties()
        props["mail.smtp.auth"] = "true"
        props["mail.smtp.starttls.enable"] = "true"
        props["mail.smtp.host"] = "smtp.gmail.com"
        props["mail.smtp.port"] = "587"

        val username = System.getenv("GMAIL_USER") ?: ""
        val password = readSecret("gmail_app_password", "GMAIL_APP_PASSWORD")

        return Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(username, password)
            }
        })
    }

    suspend fun sendOtp(toEmail: String): String {
        val code = (100000..999999).random().toString()
        val expiry = System.currentTimeMillis() + 5 * 60 * 1000

        transaction {
            OtpTokens.deleteWhere { OtpTokens.email eq toEmail }
            OtpTokens.insert {
                it[email] = toEmail
                it[OtpTokens.code] = code
                it[OtpTokens.expiry] = expiry
            }
        }

        withContext(Dispatchers.IO) {
            val message = MimeMessage(getSession())
            message.setFrom(InternetAddress(System.getenv("GMAIL_USER") ?: "nvr@aiview.vn"))
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
            message.subject = "NVR AI View - Mã xác thực bảo mật"
            message.setText("Mã xác thực của bạn là: $code\n\nMã này sẽ hết hạn sau 5 phút.")
            Transport.send(message)
        }
        return code
    }

    // Check OTP validity without consuming it (used for pre-verify before pattern draw)
    fun peekOtp(email: String, code: String): Boolean {
        val row = transaction {
            OtpTokens.selectAll().where { OtpTokens.email eq email }.firstOrNull()
        } ?: return false
        if (System.currentTimeMillis() > row[OtpTokens.expiry]) {
            transaction { OtpTokens.deleteWhere { OtpTokens.email eq email } }
            return false
        }
        return row[OtpTokens.code] == code
    }

    // Check OTP and consume it on success
    fun verifyOtp(email: String, code: String): Boolean {
        if (!peekOtp(email, code)) return false
        transaction { OtpTokens.deleteWhere { OtpTokens.email eq email } }
        return true
    }
}
