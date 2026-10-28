package vn.aiview.nvr.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import vn.aiview.nvr.cloud.CloudReconnectContext
import vn.aiview.nvr.cloud.SignalingManager
import vn.aiview.nvr.models.CloudConfig
import vn.aiview.nvr.utils.CryptoUtils

@Serializable
data class CloudConfigRequest(
    val domain: String,
    val port: Int,
    val username: String,
    val password: String,
    val serial: String,
    val enabled: Boolean
)

@Serializable
data class SerialGenerateRequest(val prototype: String)

private val SERIAL_REGEX = Regex("^VMSAV(\\d{6})$")

/**
 * Cấu hình kết nối cloud — ĐỌC VÀ GHI ĐỀU PHẢI ĐĂNG NHẬP. Đăng ký trong khối
 * `authenticate("auth-session")` của [vn.aiview.nvr.plugins.configureRouting].
 *
 * Trước 28-10-2026 cả file này nằm NGOÀI khối authenticate (để public cho callback dc-bridge),
 * nên `GET /api/cloud/config` trả domain/username/serial cho bất kỳ ai trong LAN, và
 * `POST` cùng đường đó trỏ được máy sang cloud khác mà không cần mã vẽ. Phần không cần auth
 * đã tách sang [cloudInternalRouting].
 */
fun Route.cloudRouting() {
    get("/api/cloud/config") {
        val row = transaction {
            CloudConfig.select(
                CloudConfig.domain,
                CloudConfig.port,
                CloudConfig.username,
                CloudConfig.serial,
                CloudConfig.enabled
            ).where { CloudConfig.id eq 1 }.firstOrNull()
        }
        val state = SignalingManager.connectionState
        call.respond(buildJsonObject {
            put("domain", row?.get(CloudConfig.domain) ?: "")
            put("port", row?.get(CloudConfig.port) ?: 443)
            put("username", row?.get(CloudConfig.username) ?: "")
            put("serial", row?.get(CloudConfig.serial) ?: "")
            put("enabled", row?.get(CloudConfig.enabled) ?: false)
            put("connectionState", state.name)
            val err = SignalingManager.lastError
            if (err != null) put("lastError", err) else put("lastError", JsonNull)
        })
    }

    post("/api/cloud/config") {
        val req = call.receive<CloudConfigRequest>()

        if (!SERIAL_REGEX.matches(req.serial)) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid serial format. Expected VMSAV followed by 6 digits."))
            return@post
        }
        if (req.domain.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Domain must not be blank."))
            return@post
        }
        if (req.port !in 1..65535) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Port must be between 1 and 65535."))
            return@post
        }

        // Blank password = "keep what is stored". The UI no longer ships a default password
        // (it would be readable in the JS bundle on every delivered machine), so re-saving any
        // other field must not silently wipe the credential typed at install time.
        val existingPassword = transaction {
            CloudConfig.selectAll().firstOrNull()?.get(CloudConfig.passwordEncrypted)
        }
        val encryptedPassword =
            if (req.password.isBlank() && !existingPassword.isNullOrBlank()) existingPassword
            else CryptoUtils.encrypt(req.password)

        transaction {
            CloudConfig.upsert {
                it[id] = 1
                it[domain] = req.domain
                it[port] = req.port
                it[username] = req.username
                it[passwordEncrypted] = encryptedPassword
                it[serial] = req.serial
                it[enabled] = req.enabled
            }
        }

        CloudReconnectContext.reconnect?.invoke()

        call.respond(buildJsonObject {
            put("success", true)
            put("connectionState", SignalingManager.connectionState.name)
        })
    }

    post("/api/cloud/serial/generate") {
        val req = call.receive<SerialGenerateRequest>()
        val match = SERIAL_REGEX.find(req.prototype)
        if (match == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid prototype serial format."))
            return@post
        }
        val next = (match.groupValues[1].toInt() + 1).toString().padStart(6, '0')
        call.respond(mapOf("serial" to "VMSAV$next"))
    }

}

/**
 * Endpoint dưới `/internal/` KHÔNG qua session auth, vì người gọi là máy chứ không phải người:
 * sidecar `dc-bridge` đẩy lệnh DataChannel vào `127.0.0.1:3000`, đi thẳng cổng 3000 chứ KHÔNG
 * qua nginx — nên nginx chặn hẳn `location /internal/`, và backend chỉ bind `127.0.0.1`
 * (`Application.kt`), tức là ngoài LAN không có đường nào tới đây.
 *
 * ⚠️ Thêm endpoint mới vào đây là thêm một đường KHÔNG cần đăng nhập. Chỉ đặt vào đây thứ do
 * tiến trình trên cùng máy gọi; bất cứ thứ gì người dùng/UI gọi đều thuộc [cloudRouting].
 */
fun Route.cloudInternalRouting() {
    post("/internal/dc/command") {
        val rawJson = call.receiveText()
        var reply: String? = null
        try {
            val dispatcher = vn.aiview.nvr.cloud.CommandDispatcher
            dispatcher.dispatchDataChannel(rawJson) { reply = it }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "dispatch error")))
            return@post
        }
        call.respond(reply ?: "{}")
    }
}
