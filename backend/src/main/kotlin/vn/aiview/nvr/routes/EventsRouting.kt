package vn.aiview.nvr.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.AIEvents
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.services.AppSettingsService
import vn.aiview.nvr.services.BoxCalibrationService
import vn.aiview.nvr.services.EventClipService
import vn.aiview.nvr.services.IntegrationService
import vn.aiview.nvr.services.PlaybackService
import vn.aiview.nvr.utils.AppLogger
import java.io.File

// ---- Box push wire format ----

@Serializable
private data class PushEventRequest(
    val username: String = "",
    val password: String = "",
    val camera_id: String = "",            // = NVR channel_id
    val timestamp: Long = 0,               // epoch ms — centre of the -5/+5 clip
    val ai_type: String = "",              // e.g. facerecognition / person / intrusion
    val objects: List<PushObject> = emptyList(),
    val event_id: String = "",             // optional Box id (for idempotency); NVR generates one if blank
    val snapshot_base64: String? = null,   // optional JPEG from the Box
)

@Serializable
private data class PushObject(val name: String = "", val score: Double? = null)

@Serializable
private data class PushEventResponse(val event_id: String, val status: String)

@Serializable
private data class PushErrorResponse(val error: String, val message: String)

private fun ApplicationCall.pushClientIp(): String =
    request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ?: request.origin.remoteAddress

/**
 * Public Box → NVR push endpoint. Self-authenticates with the integration credential pair
 * (same as the other /api Box endpoints), rate-limited per IP. Persists the event immediately
 * (source="box", clip_status="processing") and kicks off the async clip/snapshot cut, returning 202.
 */
fun Route.pushEventRouting() {
    route("/api") {
        post("/pushevent") {
            val endpoint = "/api/pushevent"
            val raw = call.receiveText()
            val ip = call.pushClientIp()

            IntegrationService.RateLimiter.check(ip)?.let { retryAfter ->
                call.response.headers.append(HttpHeaders.RetryAfter, retryAfter.toString())
                return@post call.respond(HttpStatusCode.TooManyRequests,
                    PushErrorResponse("TOO_MANY_REQUESTS", "Quá nhiều yêu cầu, thử lại sau."))
            }

            val req = try {
                Json { ignoreUnknownKeys = true }.decodeFromString<PushEventRequest>(raw)
            } catch (_: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest,
                    PushErrorResponse("BAD_REQUEST", "Yêu cầu không hợp lệ."))
            }

            when (IntegrationService.verifyBoxAuth(req.username, req.password)) {
                is IntegrationService.AuthResult.Disabled ->
                    return@post call.respond(HttpStatusCode.Forbidden,
                        PushErrorResponse("FORBIDDEN", "Tích hợp chưa được bật trên NVR."))
                is IntegrationService.AuthResult.Invalid ->
                    return@post call.respond(HttpStatusCode.Unauthorized,
                        PushErrorResponse("INVALID_CREDENTIALS", "Sai tên đăng nhập hoặc mật khẩu."))
                is IntegrationService.AuthResult.Ok -> { /* fall through */ }
            }

            if (req.camera_id.isBlank() || req.timestamp <= 0) {
                return@post call.respond(HttpStatusCode.BadRequest,
                    PushErrorResponse("BAD_REQUEST", "Thiếu camera_id hoặc timestamp."))
            }

            val slug = PlaybackService.channelToSlug(req.camera_id)
                ?: return@post call.respond(HttpStatusCode.NotFound,
                    PushErrorResponse("CAMERA_NOT_FOUND", "Không tìm thấy camera ${req.camera_id} trên NVR."))

            IntegrationService.recordConnection(ip, req.username, endpoint)

            val eventId = "boxpush-" + (req.event_id.takeIf { it.isNotBlank() } ?: System.currentTimeMillis().toString())
            val label = buildLabel(req.ai_type, req.objects)
            // Accept both epoch-seconds and epoch-millis: anything below ~2001-09 in ms is really seconds.
            val tsMs = if (req.timestamp < 1_000_000_000_000L) req.timestamp * 1000 else req.timestamp
            val tsSec = tsMs / 1000
            val score = req.objects.firstOrNull()?.score

            val inserted = runCatching {
                transaction {
                    // Idempotency: skip if this event_id already exists.
                    val exists = AIEvents.selectAll().where { AIEvents.id eq eventId }.firstOrNull() != null
                    if (exists) return@transaction false
                    AIEvents.insert {
                        it[id] = eventId
                        it[cameraSlug] = slug
                        it[AIEvents.label] = label
                        it[AIEvents.aiType] = req.ai_type.takeIf { it.isNotBlank() }
                        it[AIEvents.objectType] = req.objects.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                        it[AIEvents.score] = score
                        it[startTime] = tsSec - EventClipService.PRE_SEC
                        it[endTime] = tsSec + EventClipService.POST_SEC
                        it[eventSource] = "box"
                        it[clipStatus] = "processing"
                    }
                    true
                }
            }.getOrElse { e ->
                AppLogger.warn(AppLogger.Module.SYSTEM, "PUSH_EVENT", "PERSIST_FAIL", "id=$eventId err=${e.message}")
                false
            }

            if (inserted) {
                EventClipService.enqueue(eventId, slug, tsMs, req.snapshot_base64)
                AppLogger.info(AppLogger.Module.SYSTEM, "PUSH_EVENT", "ACCEPTED",
                    "id=$eventId cam=${req.camera_id}→$slug type=${req.ai_type} ts=${req.timestamp}")
            } else {
                AppLogger.info(AppLogger.Module.SYSTEM, "PUSH_EVENT", "DUPLICATE", "id=$eventId — already exists")
            }
            call.respond(HttpStatusCode.Accepted, PushEventResponse(eventId, if (inserted) "processing" else "duplicate"))
        }
    }
}

private fun buildLabel(aiType: String, objects: List<PushObject>): String {
    val name = objects.firstOrNull()?.name?.takeIf { it.isNotBlank() }
    return if (name != null) "$aiType: $name" else aiType.ifBlank { "event" }
}

// ---- Authed: event list + media serving + source-mode setting ----

@Serializable
private data class EventView(
    val id: String,
    val camera: String,
    val camera_name: String,
    val label: String,
    val ai_type: String?,
    val object_type: String?,
    val extra: String?,
    val box_x: Double?,
    val box_y: Double?,
    val box_w: Double?,
    val box_h: Double?,
    val score: Double?,
    val start_time: Long,
    val end_time: Long?,
    val source: String,
    val clip_status: String?,
    val has_clip: Boolean,
    val has_snapshot: Boolean,
    val clip_url: String?,
    val snapshot_url: String?,
)

@Serializable
private data class SourceModeView(val ai_source_mode: String)

@Serializable
private data class SourceModeUpdate(val ai_source_mode: String = AppSettingsService.MODE_ALL)

fun Route.eventsRouting() {
    // AI source mode toggle (5B). box_only hides Frigate events without touching detect/recording.
    route("/api/events/settings") {
        get {
            call.respond(SourceModeView(AppSettingsService.get(AppSettingsService.KEY_AI_SOURCE_MODE, AppSettingsService.MODE_ALL)))
        }
        put {
            val body = try { call.receive<SourceModeUpdate>() } catch (_: Exception) {
                return@put call.respond(HttpStatusCode.BadRequest, PushErrorResponse("BAD_REQUEST", "Yêu cầu không hợp lệ."))
            }
            val mode = if (body.ai_source_mode == AppSettingsService.MODE_BOX_ONLY) AppSettingsService.MODE_BOX_ONLY else AppSettingsService.MODE_ALL
            AppSettingsService.set(AppSettingsService.KEY_AI_SOURCE_MODE, mode)
            call.respond(SourceModeView(mode))
        }
    }

    // Ô cấu hình chỉnh lại khoanh vùng AI khi lệch (xem BoxCalibrationService).
    route("/api/events/box-calibration/{slug}") {
        get {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(BoxCalibrationService.get(slug))
        }
        put {
            val slug = call.parameters["slug"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val body = try { call.receive<BoxCalibrationService.BoxCalibration>() } catch (_: Exception) {
                return@put call.respond(HttpStatusCode.BadRequest, PushErrorResponse("BAD_REQUEST", "Yêu cầu không hợp lệ."))
            }
            BoxCalibrationService.set(slug, body)
            call.respond(body)
        }
    }

    // Event list from the ai_events table (unlike the legacy Frigate-only list).
    get("/api/events") {
        val sourceParam = call.request.queryParameters["source"]?.takeIf { it.isNotBlank() }
        val cameraParam = call.request.queryParameters["camera"]?.takeIf { it.isNotBlank() }
        val aiTypeParam = call.request.queryParameters["ai_type"]?.takeIf { it.isNotBlank() }
        val after = call.request.queryParameters["after"]?.toLongOrNull()   // epoch sec
        val before = call.request.queryParameters["before"]?.toLongOrNull() // epoch sec
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)

        // View filter (client-controlled): ?source=all → no filter, ?source=box|frigate → that source.
        // If the caller sends nothing, fall back to the 5B server mode (box-only) or show everything.
        // "box" là NHÓM "Bkav AI View" trên giao diện: gộp AiView Box (box) và camera Bkav tự nhận
        // diện rồi đẩy về (bkav) — cùng một hệ sinh thái, người dùng không cần phân biệt.
        val effectiveSources: List<String>? = when {
            sourceParam == "all" -> null
            sourceParam == "box" -> listOf("box", SOURCE_BKAV)
            sourceParam != null -> listOf(sourceParam)
            AppSettingsService.isBoxOnly() -> listOf("box", SOURCE_BKAV)
            else -> null
        }

        val nameBySlug = transaction {
            Cameras.selectAll().associate { it[Cameras.slug] to it[Cameras.displayName] }
        }

        val rows = transaction {
            var q = AIEvents.selectAll()
            effectiveSources?.let { list -> q = q.andWhere { AIEvents.eventSource inList list } }
            cameraParam?.let { c -> q = q.andWhere { AIEvents.cameraSlug eq c } }
            aiTypeParam?.let { t -> q = q.andWhere { AIEvents.aiType eq t } }
            after?.let { a -> q = q.andWhere { AIEvents.startTime greaterEq a } }
            before?.let { b -> q = q.andWhere { AIEvents.startTime lessEq b } }
            q.orderBy(AIEvents.startTime, SortOrder.DESC).limit(limit).map { r ->
                val id = r[AIEvents.id]
                val source = r[AIEvents.eventSource]
                val clipPath = r[AIEvents.clipPath]
                val snapPath = r[AIEvents.snapshotPath]
                val slug = r[AIEvents.cameraSlug]

                // Box push: clip + snapshot are files we cut, served by our own endpoints.
                // Frigate: point at Frigate's own thumbnail + clip through the authenticated proxy
                // (the id IS the Frigate event id). Frigate always has a thumbnail; clip needs a recording.
                val hasClipFile = !clipPath.isNullOrBlank() && File(clipPath).exists()
                val hasSnapFile = !snapPath.isNullOrBlank() && !snapPath.startsWith("/api/") && File(snapPath).exists()
                val isFrigate = source == "frigate"
                EventView(
                    id = id,
                    camera = slug,
                    camera_name = nameBySlug[slug] ?: slug,
                    label = r[AIEvents.label],
                    ai_type = r[AIEvents.aiType],
                    object_type = r[AIEvents.objectType],
                    extra = r[AIEvents.extra],
                    box_x = r[AIEvents.boxX],
                    box_y = r[AIEvents.boxY],
                    box_w = r[AIEvents.boxW],
                    box_h = r[AIEvents.boxH],
                    score = r[AIEvents.score],
                    start_time = r[AIEvents.startTime],
                    end_time = r[AIEvents.endTime],
                    source = source,
                    clip_status = r[AIEvents.clipStatus],
                    has_clip = if (isFrigate) true else hasClipFile,
                    has_snapshot = if (isFrigate) true else hasSnapFile,
                    clip_url = when {
                        isFrigate -> "/api/frigate/api/events/$id/clip.mp4"
                        hasClipFile -> "/api/events/$id/clip.mp4"
                        else -> null
                    },
                    snapshot_url = when {
                        isFrigate -> "/api/frigate/api/events/$id/thumbnail.jpg"
                        hasSnapFile -> "/api/events/$id/snapshot.jpg"
                        else -> null
                    },
                )
            }
        }
        call.respond(rows)
    }

    // Serve the cut clip (file-backed, Box push flow only).
    get("/api/events/{id}/clip.mp4") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val path = transaction {
            AIEvents.selectAll().where { AIEvents.id eq id }.firstOrNull()?.get(AIEvents.clipPath)
        }
        val file = path?.let { File(it) }
        if (file == null || !file.exists()) return@get call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.ContentType, "video/mp4")
        call.respondFile(file)
    }

    // Serve the still snapshot (file-backed, Box push flow only).
    get("/api/events/{id}/snapshot.jpg") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val path = transaction {
            AIEvents.selectAll().where { AIEvents.id eq id }.firstOrNull()?.get(AIEvents.snapshotPath)
        }
        val file = path?.takeIf { !it.startsWith("/api/") }?.let { File(it) }
        if (file == null || !file.exists()) return@get call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.ContentType, "image/jpeg")
        call.respondFile(file)
    }
}
