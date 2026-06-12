package vn.aiview.nvr.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.services.IntegrationService
import vn.aiview.nvr.services.StreamOnlineTracker

// ---- Wire formats (match NVR_AIView_Integration_Protocol v1.1 exactly) ----

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

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) =
    respond(status, ErrorResponse(code, message))

/**
 * Client IP for rate limiting. Prefers the first X-Forwarded-For hop (set by nginx) so distinct
 * Boxes keep distinct buckets behind the reverse proxy; falls back to the direct socket peer.
 */
private fun ApplicationCall.clientIp(): String =
    request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ?: request.origin.remoteHost

/**
 * Public AiView Box integration endpoints (v1.1 §3). NOT inside authenticate("auth-session") —
 * these self-authenticate with the dedicated integration credential pair (§8 hướng A).
 */
fun Route.integrationRouting() {
    route("/api") {

        // §3.1 — version + limits. No auth.
        get("/getintegrationinfo") {
            call.respond(
                IntegrationInfo(
                    protocol_version = IntegrationService.PROTOCOL_VERSION,
                    nvr_version = appVersion,
                    max_cameras = IntegrationService.MAX_CAMERAS,
                    rtsp_auth = IntegrationService.RTSP_AUTH,
                )
            )
        }

        // §3.2 — discovery: list not-yet-added cameras + restream URLs. Rate-limit → auth.
        post("/getprofilecamera") {
            val ip = call.clientIp()
            IntegrationService.RateLimiter.check(ip)?.let { retryAfter ->
                call.response.headers.append(HttpHeaders.RetryAfter, retryAfter.toString())
                return@post call.respondError(HttpStatusCode.TooManyRequests, "TOO_MANY_REQUESTS", "Quá nhiều yêu cầu, thử lại sau.")
            }

            val req = try {
                call.receive<ProfileRequest>()
            } catch (_: Exception) {
                return@post call.respondError(HttpStatusCode.BadRequest, "BAD_REQUEST", "Yêu cầu không hợp lệ.")
            }

            when (IntegrationService.verifyBoxAuth(req.username, req.password)) {
                is IntegrationService.AuthResult.Disabled ->
                    return@post call.respondError(HttpStatusCode.Forbidden, "FORBIDDEN", "Tích hợp chưa được bật trên NVR.")
                is IntegrationService.AuthResult.Invalid ->
                    return@post call.respondError(HttpStatusCode.Unauthorized, "INVALID_CREDENTIALS", "Sai tên đăng nhập hoặc mật khẩu.")
                is IntegrationService.AuthResult.Ok -> { /* fall through */ }
            }

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
            call.respond(result)
        }

        // §3.3 — health-check by camera_id. No auth (no stream URLs leaked). Rate-limited.
        post("/getcamerastatus") {
            val ip = call.clientIp()
            IntegrationService.RateLimiter.check(ip)?.let { retryAfter ->
                call.response.headers.append(HttpHeaders.RetryAfter, retryAfter.toString())
                return@post call.respondError(HttpStatusCode.TooManyRequests, "TOO_MANY_REQUESTS", "Quá nhiều yêu cầu, thử lại sau.")
            }

            val req = try {
                call.receive<StatusRequest>()
            } catch (_: Exception) {
                return@post call.respondError(HttpStatusCode.BadRequest, "BAD_REQUEST", "Yêu cầu không hợp lệ.")
            }

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
            call.respond(result)
        }
    }
}

// ---- Admin settings (protected — NVR operator manages enable + credentials) ----

@Serializable
private data class IntegrationSettingsView(val enabled: Boolean, val username: String, val hasPassword: Boolean)

@Serializable
private data class IntegrationSettingsUpdate(
    val enabled: Boolean = false,
    val username: String = "",
    val password: String? = null,
)

/**
 * Admin endpoints — register INSIDE authenticate("auth-session"). Never returns the password hash.
 */
fun Route.integrationAdminRouting() {
    route("/api/integration/settings") {
        get {
            val s = IntegrationService.readSettings()
            call.respond(IntegrationSettingsView(s.enabled, s.username, s.passwordHash.isNotBlank()))
        }
        put {
            val body = try {
                call.receive<IntegrationSettingsUpdate>()
            } catch (_: Exception) {
                return@put call.respondError(HttpStatusCode.BadRequest, "BAD_REQUEST", "Yêu cầu không hợp lệ.")
            }
            // Enabling requires a username and an existing-or-new password (avoid an open door).
            val current = IntegrationService.readSettings()
            val willHavePassword = !body.password.isNullOrBlank() || current.passwordHash.isNotBlank()
            if (body.enabled && (body.username.isBlank() || !willHavePassword)) {
                return@put call.respondError(
                    HttpStatusCode.BadRequest, "BAD_REQUEST",
                    "Bật tích hợp cần có tên đăng nhập và mật khẩu."
                )
            }
            IntegrationService.updateSettings(body.enabled, body.username, body.password)
            val s = IntegrationService.readSettings()
            call.respond(IntegrationSettingsView(s.enabled, s.username, s.passwordHash.isNotBlank()))
        }
    }
}
