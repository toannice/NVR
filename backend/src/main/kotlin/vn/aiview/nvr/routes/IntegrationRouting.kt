package vn.aiview.nvr.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.reflect.typeInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.services.IntegrationService
import vn.aiview.nvr.services.StreamOnlineTracker

// ---- Wire formats (match NVR_AIView_Integration_Protocol v1.2 exactly) ----

@Serializable
private data class IntegrationInfo(
    val protocol_version: String,
    val nvr_version: String,
    val max_cameras: Int,
    val rtsp_auth: Boolean,
)

@Serializable
private data class ErrorResponse(val error: String, val message: String)

@Serializable
private data class ProfileRequest(
    val username: String = "",
    val password: String = "",
    val already_added_camera_ids: List<String> = emptyList(),
)

@Serializable
private data class BoxCameraProfile(
    val camera_id: String,
    val name: String,
    val main_stream_url: String,
    val sub_stream_url: String?,
    val online: Boolean,
)

@Serializable
private data class StatusRequest(val camera_ids: List<String> = emptyList())

@Serializable
private data class BoxCameraStatusItem(
    val camera_id: String,
    val exists: Boolean,
    val online: Boolean,
)

/**
 * Client IP for rate limiting. Prefers the first X-Forwarded-For hop (set by nginx) so distinct
 * Boxes keep distinct buckets behind the reverse proxy; falls back to the direct socket peer.
 */
private fun ApplicationCall.clientIp(): String =
    request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ?: request.origin.remoteAddress   // numeric IP (not remoteHost, which may reverse-DNS to a hostname like "localhost")

// ---- Audit logging (full request/response trail of every Box call, e.g. from 192.168.2.2) ----

private val auditLog = LoggerFactory.getLogger("IntegrationAudit")
private val auditJson = Json { encodeDefaults = true }

/**
 * Mask secrets so credentials never land in a log file: JSON `"password"` values AND any
 * `user:pass@` embedded in an rtsp(s):// URL (relevant once `rtsp_auth=true`).
 */
private fun redact(raw: String): String = raw
    .replace(Regex("(\"password\"\\s*:\\s*\")(.*?)(\")"), "$1***$3")
    .replace(Regex("(rtsps?://[^:/?#@\\s]+:)([^@/\\s]+)(@)"), "$1***$3")

/** Source descriptor: effective client IP + raw socket peer + X-Forwarded-For (for filtering). */
private fun ApplicationCall.source(): String {
    val peer = request.origin.remoteAddress
    val xff = request.headers["X-Forwarded-For"] ?: "-"
    return "ip=${clientIp()} peer=$peer xff=$xff"
}

private fun ApplicationCall.logReq(endpoint: String, rawBody: String) {
    val payload = redact(rawBody).ifBlank { "<empty>" }
    auditLog.info("REQ ${request.httpMethod.value} $endpoint | ${source()} | payload=$payload")
}

/** Log the response (status + body) then send it. */
private suspend inline fun <reified T> ApplicationCall.respondLogged(
    endpoint: String, status: HttpStatusCode, body: T
) {
    val rendered = redact(try { auditJson.encodeToString(body) } catch (_: Exception) { body.toString() })
    auditLog.info("RESP $endpoint | ${source()} | status=${status.value} | response=$rendered")
    respond(status, body as Any, typeInfo<T>())
}

private suspend fun ApplicationCall.respondError(
    endpoint: String, status: HttpStatusCode, code: String, message: String
) = respondLogged(endpoint, status, ErrorResponse(code, message))

/**
 * Public AiView Box integration endpoints (v1.2 §3). NOT inside authenticate("auth-session") —
 * these self-authenticate with the dedicated integration credential pair (§8 hướng A).
 */
fun Route.integrationRouting() {
    route("/api") {

        // §3.1 — version + limits. No auth.
        get("/getintegrationinfo") {
            val endpoint = "/api/getintegrationinfo"
            call.logReq(endpoint, "")
            try {
                call.respondLogged(
                    endpoint, HttpStatusCode.OK,
                    IntegrationInfo(
                        protocol_version = IntegrationService.PROTOCOL_VERSION,
                        nvr_version = appVersion,
                        max_cameras = IntegrationService.MAX_CAMERAS,
                        rtsp_auth = IntegrationService.RTSP_AUTH,
                    )
                )
            } catch (_: Exception) {
                call.respondError(endpoint, HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "Lỗi hệ thống NVR.")
            }
        }

        // §3.2 — discovery: list not-yet-added cameras + restream URLs. Rate-limit → auth.
        post("/getprofilecamera") {
            val endpoint = "/api/getprofilecamera"
            val raw = call.receiveText()
            call.logReq(endpoint, raw)
            try {
                val ip = call.clientIp()
                IntegrationService.RateLimiter.check(ip)?.let { retryAfter ->
                    call.response.headers.append(HttpHeaders.RetryAfter, retryAfter.toString())
                    return@post call.respondError(endpoint, HttpStatusCode.TooManyRequests, "TOO_MANY_REQUESTS", "Quá nhiều yêu cầu, thử lại sau.")
                }

                val req = try {
                    auditJson.decodeFromString<ProfileRequest>(raw)
                } catch (_: Exception) {
                    return@post call.respondError(endpoint, HttpStatusCode.BadRequest, "BAD_REQUEST", "Yêu cầu không hợp lệ.")
                }

                when (IntegrationService.verifyBoxAuth(req.username, req.password)) {
                    is IntegrationService.AuthResult.Disabled ->
                        return@post call.respondError(endpoint, HttpStatusCode.Forbidden, "FORBIDDEN", "Tích hợp chưa được bật trên NVR.")
                    is IntegrationService.AuthResult.Invalid ->
                        return@post call.respondError(endpoint, HttpStatusCode.Unauthorized, "INVALID_CREDENTIALS", "Sai tên đăng nhập hoặc mật khẩu.")
                    is IntegrationService.AuthResult.Ok -> { /* fall through */ }
                }

                // §4.1 — 503 if Frigate/go2rtc not yet ready (stream URLs would be dead).
                if (!IntegrationService.isFrigateReady()) {
                    return@post call.respondError(endpoint, HttpStatusCode.ServiceUnavailable, "SERVICE_UNAVAILABLE", "NVR đang bận, thử lại sau.")
                }

                // Successful auth → record this Box IP so the admin can see who connected.
                IntegrationService.recordConnection(ip, req.username, endpoint)

                val excluded = req.already_added_camera_ids.toSet()
                val result = transaction {
                    Cameras.selectAll()
                        .mapNotNull { row ->
                            val chId = row[Cameras.channelId] ?: return@mapNotNull null   // need channelId for stream name (C2)
                            if (chId in excluded) return@mapNotNull null
                            val hasSub = !row[Cameras.subRtspUrl].isNullOrBlank()
                            BoxCameraProfile(
                                camera_id = chId,
                                name = row[Cameras.displayName],
                                main_stream_url = IntegrationService.streamUrl(chId, sub = false),
                                sub_stream_url = if (hasSub) IntegrationService.streamUrl(chId, sub = true) else null,
                                online = StreamOnlineTracker.isOnline(chId),
                            )
                        }
                }
                call.respondLogged(endpoint, HttpStatusCode.OK, result)
            } catch (_: Exception) {
                call.respondError(endpoint, HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "Lỗi hệ thống NVR.")
            }
        }

        // §3.3 — health-check by camera_id. No auth (no stream URLs leaked). Rate-limited.
        post("/getcamerastatus") {
            val endpoint = "/api/getcamerastatus"
            val raw = call.receiveText()
            call.logReq(endpoint, raw)
            try {
                val ip = call.clientIp()
                IntegrationService.RateLimiter.check(ip)?.let { retryAfter ->
                    call.response.headers.append(HttpHeaders.RetryAfter, retryAfter.toString())
                    return@post call.respondError(endpoint, HttpStatusCode.TooManyRequests, "TOO_MANY_REQUESTS", "Quá nhiều yêu cầu, thử lại sau.")
                }

                val req = try {
                    auditJson.decodeFromString<StatusRequest>(raw)
                } catch (_: Exception) {
                    return@post call.respondError(endpoint, HttpStatusCode.BadRequest, "BAD_REQUEST", "Yêu cầu không hợp lệ.")
                }

                // Heartbeat: bump lastSeen for a Box IP that already authenticated (never creates a row).
                IntegrationService.touchConnection(ip, endpoint)

                val existing: Set<String> = transaction {
                    Cameras.selectAll().mapNotNull { it[Cameras.channelId] }.toSet()
                }
                val result = req.camera_ids.map { id ->
                    BoxCameraStatusItem(
                        camera_id = id,
                        exists = id in existing,
                        online = id in existing && StreamOnlineTracker.isOnline(id),
                    )
                }
                call.respondLogged(endpoint, HttpStatusCode.OK, result)
            } catch (_: Exception) {
                call.respondError(endpoint, HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "Lỗi hệ thống NVR.")
            }
        }
    }
}

// ---- Admin settings (protected — NVR operator manages enable + credentials) ----

@Serializable
private data class IntegrationSettingsView(
    val enabled: Boolean,
    val username: String,
    val hasPassword: Boolean,
    val passwordLen: Int,
    val box_ip: String,
    val box_port: Int,
)

@Serializable
private data class ConnectionView(
    val ip: String,
    val username: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val hitCount: Long,
    val lastEndpoint: String,
)

@Serializable
private data class IntegrationSettingsUpdate(
    val enabled: Boolean = false,
    val username: String = "",
    val password: String? = null,
    val box_ip: String = "",
    val box_port: Int = 8088,
)

/**
 * Admin endpoints — register INSIDE authenticate("auth-session"). Never returns the password hash.
 */
fun Route.integrationAdminRouting() {
    route("/api/integration/settings") {
        get {
            val s = IntegrationService.readSettings()
            call.respond(IntegrationSettingsView(s.enabled, s.username, s.passwordHash.isNotBlank(), s.passwordLen, s.boxIp, s.boxPort))
        }
        put {
            val body = try {
                call.receive<IntegrationSettingsUpdate>()
            } catch (_: Exception) {
                return@put call.respondError("/api/integration/settings", HttpStatusCode.BadRequest, "BAD_REQUEST", "Yêu cầu không hợp lệ.")
            }
            // Enabling requires a username and an existing-or-new password (avoid an open door).
            val current = IntegrationService.readSettings()
            val willHavePassword = !body.password.isNullOrBlank() || current.passwordHash.isNotBlank()
            if (body.enabled && (body.username.isBlank() || !willHavePassword)) {
                return@put call.respondError(
                    "/api/integration/settings", HttpStatusCode.BadRequest, "BAD_REQUEST",
                    "Bật tích hợp cần có tên đăng nhập và mật khẩu."
                )
            }
            IntegrationService.updateSettings(body.enabled, body.username, body.password, body.box_ip, body.box_port)
            IntegrationService.applyBoxClient()
            val s = IntegrationService.readSettings()
            call.respond(IntegrationSettingsView(s.enabled, s.username, s.passwordHash.isNotBlank(), s.passwordLen, s.boxIp, s.boxPort))
        }
    }

    // List Box IPs that have successfully connected (admin visibility).
    get("/api/integration/connections") {
        val conns = IntegrationService.listConnections().map {
            ConnectionView(it.ip, it.username, it.firstSeen, it.lastSeen, it.hitCount, it.lastEndpoint)
        }
        call.respond(conns)
    }
}
