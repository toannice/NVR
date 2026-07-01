package vn.aiview.nvr.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import vn.aiview.nvr.cloud.CloudReconnectContext
import vn.aiview.nvr.cloud.SignalingManager
import vn.aiview.nvr.models.CloudConfig
import vn.aiview.nvr.services.PlaybackStreamer
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

        val encryptedPassword = CryptoUtils.encrypt(req.password)

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

    // Real-time MPEG-TS stream served by PlaybackStreamer (ffmpeg -re subprocess).
    // go2rtc (inside Frigate) fetches this from the bridge gateway IP.
    // The -re flag in the subprocess paces delivery, giving go2rtc correct RTP timestamps.
    get("/internal/playback-ts/{name}") {
        val name = call.parameters["name"] ?: run {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        if (!PlaybackStreamer.isRunning(name)) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(HttpHeaders.ContentType, "video/mp2t")
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondOutputStream(contentType = ContentType("video", "mp2t")) {
            PlaybackStreamer.pipeTo(name, this)
        }
    }

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
