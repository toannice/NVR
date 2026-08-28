package vn.aiview.nvr.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.AIEvents
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.services.EventClipService
import vn.aiview.nvr.services.IntegrationService
import vn.aiview.nvr.utils.AppLogger
import java.time.OffsetDateTime

/**
 * Camera Bkav (BkavAIEvent / BkavAICore) tự chạy AI trên thân máy và POST thẳng sự kiện về NVR —
 * không qua AiView Box, không qua Frigate. NVR đóng vai server nhận, cắt clip −5/+5 quanh mốc thời
 * gian camera báo, rồi lưu vào "Cấu hình AI → Sự kiện" chung với các nguồn khác (`source="bkav"`).
 *
 * Payload đúng như camera phát ra (chỉ đọc các trường cần, phần còn lại bỏ qua):
 * ```json
 * {"extras":{"cam_id":96,"objects":[{"confidence":0.9998,"type":"car",
 *   "license":{"label":"30A87662","locations":[793,208,68,34]},
 *   "locations":[542,110,369,173],"zone":{"id":199,"name":"Full screen"}}]},
 *  "tags":{"cam_id":96,"monitor_id":188},"time":"2026-08-03T10:41:58+07:00",
 *  "type":"vehicledetection","uuid":"bed2294d-b28d-87a2-c3d7-044072eeaba8"}
 * ```
 *
 * XÁC THỰC: camera không gửi kèm user/pass (xem log gốc — payload thuần dữ liệu), nên chốt chặn duy
 * nhất khả dụng là **nguồn gọi phải là một camera NVR đang quản lý**: IP gửi request khớp
 * `cameras.ip`/`cameras.local_ip`, hoặc `cam_id` khớp `cameras.channel_id`. Không khớp → 404 và
 * KHÔNG ghi gì. Cộng thêm rate-limit theo IP dùng chung với các endpoint tích hợp.
 */

@Serializable
private data class BkavLicense(val label: String = "")

@Serializable
private data class BkavZone(val name: String = "")

@Serializable
private data class BkavObject(
    val type: String = "",
    val confidence: Double? = null,
    val license: BkavLicense? = null,
    val zone: BkavZone? = null,
    // [x, y, w, h] tính bằng pixel theo khung hình luồng chính camera gửi kèm sự kiện.
    val locations: List<Double> = emptyList(),
)

@Serializable
private data class BkavExtras(
    val cam_id: Int? = null,
    val objects: List<BkavObject> = emptyList(),
)

@Serializable
private data class BkavTags(val cam_id: Int? = null, val monitor_id: Int? = null)

@Serializable
private data class BkavEventRequest(
    val uuid: String = "",
    val type: String = "",
    val time: String = "",
    val extras: BkavExtras = BkavExtras(),
    val tags: BkavTags = BkavTags(),
)

@Serializable
private data class BkavEventResponse(val event_id: String, val status: String)

@Serializable
private data class BkavPingResponse(val recognized: Boolean, val camera: String?)

@Serializable
private data class BkavErrorResponse(val error: String, val message: String)

private fun ApplicationCall.bkavClientIp(): String =
    request.headers["X-Real-IP"]?.trim()?.takeIf { it.isNotBlank() }
        ?: request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ?: request.origin.remoteAddress

fun Route.bkavEventRouting() {
    /**
     * Bắt tay cho BkavEventBridge chạy trên thân camera.
     *
     * Trước khi có đường này, camera muốn biết "NVR nào đang quản lý tôi" thì phải GỬI THẬT một
     * sự kiện rồi nhìn mã trả về — nghĩa là cứ khởi động lại là sinh một gói thừa (NVR chống
     * trùng nên vô hại, nhưng bẩn). Đường này trả lời đúng câu hỏi đó mà không có tác dụng phụ.
     *
     * LUÔN trả 200 khi tuyến tồn tại; kết quả nhận diện nằm trong trường `recognized`. Cố ý
     * KHÔNG dùng 404 cho "không nhận ra camera": camera cần phân biệt được "NVR đời cũ chưa có
     * tuyến này" (404 thật) với "NVR có tuyến, nhưng chưa add camera" (200 + recognized=false).
     * Lẫn hai ca đó thì camera sẽ loại nhầm một NVR hoàn toàn tốt.
     */
    get("/api/bkav/ping") {
        val ip = call.bkavClientIp()
        val slug = resolveCameraSlug(ip, null)
        AppLogger.info(
            AppLogger.Module.EVENT, "BKAV_EVENT", "PING",
            "ip=$ip → ${slug ?: "không khớp camera nào"}"
        )
        call.respond(HttpStatusCode.OK, BkavPingResponse(recognized = slug != null, camera = slug))
    }

    post("/api/bkav/event") {
        val ip = call.bkavClientIp()

        IntegrationService.RateLimiter.check(ip)?.let { retryAfter ->
            call.response.headers.append(HttpHeaders.RetryAfter, retryAfter.toString())
            return@post call.respond(
                HttpStatusCode.TooManyRequests,
                BkavErrorResponse("TOO_MANY_REQUESTS", "Quá nhiều yêu cầu, thử lại sau.")
            )
        }

        val raw = call.receiveText()
        val req = try {
            Json { ignoreUnknownKeys = true }.decodeFromString<BkavEventRequest>(raw)
        } catch (e: Exception) {
            AppLogger.warn(
                AppLogger.Module.EVENT, "BKAV_EVENT", "PARSE_FAIL",
                "ip=$ip err=${e.message} body=${raw.take(200)}"
            )
            return@post call.respond(
                HttpStatusCode.BadRequest,
                BkavErrorResponse("BAD_REQUEST", "Payload không đọc được.")
            )
        }

        val camId = req.extras.cam_id ?: req.tags.cam_id
        val slug = resolveCameraSlug(ip, camId)
        if (slug == null) {
            AppLogger.warn(
                AppLogger.Module.EVENT, "BKAV_EVENT", "CAMERA_NOT_FOUND",
                "ip=$ip cam_id=$camId type=${req.type} — không khớp camera nào trong NVR, bỏ qua"
            )
            return@post call.respond(
                HttpStatusCode.NotFound,
                BkavErrorResponse("CAMERA_NOT_FOUND", "Không nhận ra camera gửi sự kiện.")
            )
        }

        val tsMs = parseEventTime(req.time)
        if (tsMs == null) {
            AppLogger.warn(
                AppLogger.Module.EVENT, "BKAV_EVENT", "BAD_TIMESTAMP",
                "ip=$ip slug=$slug time='${req.time}' — không parse được, bỏ qua"
            )
            return@post call.respond(
                HttpStatusCode.BadRequest,
                BkavErrorResponse("BAD_REQUEST", "Trường time không hợp lệ.")
            )
        }
        val tsSec = tsMs / 1000

        val label = buildBkavLabel(req.type, req.extras.objects)
        val firstObject = req.extras.objects.firstOrNull()
        val score = firstObject?.confidence
        // uuid ổn định theo sự kiện → chống nhận trùng khi camera gửi lại.
        val eventId = "bkav-" + (req.uuid.takeIf { it.isNotBlank() } ?: "$slug-$tsSec")

        val inserted = runCatching {
            transaction {
                // Trùng theo id.
                if (AIEvents.selectAll().where { AIEvents.id eq eventId }.firstOrNull() != null) {
                    return@transaction false
                }
                // Trùng theo nội dung: BkavAIEvent và BkavAICore cùng đẩy MỘT sự kiện với hai uuid
                // khác nhau (thấy rõ trong log gốc) — nếu chỉ chặn theo uuid thì mỗi lần xe đi qua
                // sẽ sinh hai thẻ y hệt và cắt clip hai lần.
                val dup = AIEvents.selectAll().where {
                    (AIEvents.cameraSlug eq slug) and
                        (AIEvents.eventSource eq SOURCE_BKAV) and
                        (AIEvents.label eq label) and
                        (AIEvents.startTime eq tsSec - EventClipService.PRE_SEC)
                }.firstOrNull()
                if (dup != null) return@transaction false

                AIEvents.insert {
                    it[id] = eventId
                    it[cameraSlug] = slug
                    it[AIEvents.label] = label
                    it[AIEvents.aiType] = req.type.takeIf { it.isNotBlank() }
                    it[AIEvents.objectType] = firstObject?.type?.takeIf { it.isNotBlank() }
                    it[AIEvents.extra] = firstObject?.license?.label?.takeIf { it.isNotBlank() }
                    it[AIEvents.score] = score
                    it[startTime] = tsSec - EventClipService.PRE_SEC
                    it[endTime] = tsSec + EventClipService.POST_SEC
                    it[eventSource] = SOURCE_BKAV
                    it[clipStatus] = "processing"
                }
                true
            }
        }.getOrElse { e ->
            AppLogger.error(
                AppLogger.Module.EVENT, "BKAV_EVENT", "PERSIST_FAIL",
                "id=$eventId slug=$slug err=${e.message}", e
            )
            return@post call.respond(
                HttpStatusCode.InternalServerError,
                BkavErrorResponse("PERSIST_FAILED", "Không lưu được sự kiện.")
            )
        }

        if (inserted) {
            // Camera chỉ gửi ĐƯỜNG DẪN ảnh trên thân máy nó (pathImage) — NVR không đọc được, nên
            // ảnh đại diện do EventClipService tự trích từ chính clip vừa cắt.
            val boxPixel = firstObject?.locations?.takeIf { it.size == 4 }
            EventClipService.enqueue(eventId, slug, tsMs, snapshotBase64 = null, boxPixel = boxPixel)
            AppLogger.info(
                AppLogger.Module.EVENT, "BKAV_EVENT", "ACCEPTED",
                "id=$eventId ip=$ip cam_id=$camId→$slug type=${req.type} label='$label' ts=$tsSec"
            )
        } else {
            AppLogger.info(
                AppLogger.Module.EVENT, "BKAV_EVENT", "DUPLICATE",
                "id=$eventId slug=$slug label='$label' ts=$tsSec — đã có, không cắt clip lại"
            )
        }

        call.respond(
            HttpStatusCode.Accepted,
            BkavEventResponse(eventId, if (inserted) "processing" else "duplicate")
        )
    }
}

const val SOURCE_BKAV = "bkav"

/**
 * IP gửi request là chốt chính (một camera một IP, không giả được từ ngoài LAN vì nginx chỉ nghe
 * trong mạng Box); `cam_id` chỉ là đường dự phòng khi camera đứng sau NAT/gateway biên.
 */
private fun resolveCameraSlug(ip: String, camId: Int?): String? = runCatching {
    transaction {
        val normalized = ip.removePrefix("::ffff:")
        Cameras.selectAll().where { (Cameras.ip eq normalized) or (Cameras.localIp eq normalized) }
            .firstOrNull()?.get(Cameras.slug)
            ?: camId?.let { id ->
                Cameras.selectAll().where { Cameras.channelId eq id.toString() }
                    .firstOrNull()?.get(Cameras.slug)
            }
    }
}.getOrElse { e ->
    AppLogger.error(AppLogger.Module.EVENT, "BKAV_EVENT", "RESOLVE_FAIL", "ip=$ip cam_id=$camId err=${e.message}", e)
    null
}

/** "2026-08-03T10:41:58+07:00" → epoch ms. Null nếu không parse được (không đoán bừa giờ hiện tại). */
private fun parseEventTime(raw: String): Long? {
    if (raw.isBlank()) return null
    return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
}

/**
 * Nhãn hiển thị trên thẻ sự kiện. Biển số là thứ người trực camera cần thấy ngay nên đưa vào nhãn
 * thay vì chôn trong JSON: "vehicledetection: car (30A87662)".
 */
private fun buildBkavLabel(type: String, objects: List<BkavObject>): String {
    val first = objects.firstOrNull()
    val kind = first?.type?.takeIf { it.isNotBlank() }
    val plate = first?.license?.label?.takeIf { it.isNotBlank() }
    val base = when {
        kind != null && type.isNotBlank() -> "$type: $kind"
        kind != null -> kind
        else -> type.ifBlank { "event" }
    }
    return if (plate != null) "$base ($plate)" else base
}
