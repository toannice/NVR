package vn.aiview.nvr.services

import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object EmailService {
    private val otpCache = ConcurrentHashMap<String, OtpData>()
    
    data class OtpData(val code: String, val expiry: Long)

    private fun getSession(): Session {
        val props = Properties()
        props["mail.smtp.auth"] = "true"
        props["mail.smtp.starttls.enable"] = "true"
        props["mail.smtp.host"] = "smtp.gmail.com"
        props["mail.smtp.port"] = "587"

        // In a real setup, these would come from .env or Config
        val username = System.getenv("GMAIL_USER") ?: ""
        val password = System.getenv("GMAIL_APP_PASSWORD") ?: ""

        return Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(username, password)
            }
        })
    }

    suspend fun sendOtp(toEmail: String): String {
        val code = (100000..999999).random().toString()
        val expiry = System.currentTimeMillis() + 5 * 60 * 1000 // 5 minutes TTL
        
        otpCache[toEmail] = OtpData(code, expiry)

        withContext(Dispatchers.IO) {
            try {
                val message = MimeMessage(getSession())
                message.setFrom(InternetAddress(System.getenv("GMAIL_USER") ?: "nvr@aiview.vn"))
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
                message.subject = "NVR AI View - Mã xác thực bảo mật"
                message.setText("Mã xác thực của bạn là: $code\n\nMã này sẽ hết hạn sau 5 phút.")

                Transport.send(message)
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }
        return code
    }

    fun verifyOtp(email: String, code: String): Boolean {
        val data = otpCache[email] ?: return false
        if (System.currentTimeMillis() > data.expiry) {
            otpCache.remove(email)
            return false
        }
        if (data.code == code) {
            otpCache.remove(email)
            return true
        }
        return false
    }
}
