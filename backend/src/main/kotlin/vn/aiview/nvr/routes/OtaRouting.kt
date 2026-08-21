package vn.aiview.nvr.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import vn.aiview.nvr.utils.AppLogger
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * API cập nhật hệ thống (OTA) cho Web UI LAN.
 *
 * ─── Vai của backend ở đây RẤT HẸP, và đó là chủ ý ──────────────────────────────────────
 * `nvr-wrapper` là con phơi ra LAN, nên nó CHỈ:
 *   · nhận file upload, ghi vào data/ota/incoming/
 *   · đọc status.json / state.json để hiển thị
 *   · ghi request.json để nhờ watchdog làm
 *
 * Nó KHÔNG verify chữ ký, KHÔNG `docker load`, KHÔNG apply. Hai lý do:
 *   1. Giữ quyền của con phơi ra LAN ở mức nhỏ nhất.
 *   2. Một bộ verify duy nhất (trong watchdog) ⇒ không có chuyện hai module lệch logic. Nếu
 *      backend cũng verify, sẽ có ngày backend nói "gói hợp lệ" mà watchdog từ chối, và người
 *      vận hành không biết tin bên nào.
 *
 * Và về mặt kỹ thuật thì wrapper KHÔNG THỂ tự apply: `docker compose up -d nvr-wrapper` sẽ
 * giết chính tiến trình đang chạy lệnh đó ngay giữa lệnh. Chi tiết phân vai: OtaLayout.kt.
 *
 * ─── Vì sao raw body chứ không multipart ────────────────────────────────────────────────
 * Gói có thể tới 4GB. `receiveMultipart()` phải parse boundary và dễ vô tình đệm vào RAM;
 * `receiveChannel()` thì chỉ là một luồng byte, copy thẳng ra đĩa, dùng bộ nhớ hằng số. Tên
 * file đi qua query param. Ít code hơn, không có edge case boundary, và nginx với
 * `proxy_request_buffering off` đẩy thẳng qua không đệm.
 */

@Serializable
private data class OtaUploadResult(
    val ok: Boolean,
    val filename: String,
    val size: Long,
    val sha256: String,
)

@Serializable
private data class OtaPackageInfo(
    val filename: String,
    val size: Long,
    val uploadedAt: String,
)

@Serializable
private data class OtaError(val error: String, val detail: String? = null)

@Serializable
private data class OtaAck(val ok: Boolean, val message: String, val requestId: String)

/** Vị trí thư mục OTA theo góc nhìn của container nvr-wrapper (mount ./data:/app/data). */
private object OtaPaths {
    val root       = File(System.getenv("OTA_DIR")?.takeIf { it.isNotBlank() } ?: "data/ota")
    val incoming   get() = File(root, "incoming")
    val stateFile  get() = File(root, "state.json")
    val statusFile get() = File(root, "status.json")
    val requestFile get() = File(root, "request.json")

    fun ensureDirs() {
        root.mkdirs()
        incoming.mkdirs()
    }
}

/** Trần kích thước gói. 4GB đủ cho gói có cả Frigate; đặt trần để một request không lấp đĩa. */
private val maxUploadBytes: Long =
    System.getenv("OTA_MAX_UPLOAD_BYTES")?.toLongOrNull() ?: (4L * 1024 * 1024 * 1024)

private val jsonOut = Json { prettyPrint = false; encodeDefaults = true }

/**
 * Làm sạch tên file người dùng gửi lên.
 *
 * Trả null nếu không dùng được. Chặt tay có chủ ý: tên này thành đường dẫn file ghi bằng
 * quyền của container, nên chỉ nhận chữ-số-gạch-chấm và đúng ba đuôi đã biết. Cắt lấy
 * basename trước để "../../etc/passwd" thành "passwd" rồi vẫn phải qua vòng ký tự.
 */
private fun sanitizeFilename(raw: String?): String? {
    val base = raw?.trim()?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
    if (base.isEmpty() || base.length > 128) return null
    if (base.startsWith(".")) return null                     // .bashrc, .env…
    if (!base.all { it.isLetterOrDigit() || it in "._-" }) return null
    val lower = base.lowercase()
    val okExt = lower.endsWith(".nvrup") || lower.endsWith(".tar.gz") || lower.endsWith(".zip")
    if (!okExt) return null
    return base
}

/**
 * Dò định dạng bằng MAGIC BYTES, không tin đuôi file.
 *
 * `.nvrup` chỉ là nhãn — nội dung là tar.gz. Người dùng rất hay đổi tên file, và một file
 * text bị đổi tên thành .nvrup phải bị chặn NGAY ở đây thay vì đi tiếp tới watchdog rồi
 * thất bại ở một bước xa hơn với thông báo khó hiểu.
 */
private fun sniffArchive(f: File): String? {
    val head = ByteArray(4)
    val n = f.inputStream().use { it.read(head) }
    if (n < 2) return null
    val b0 = head[0].toInt() and 0xFF
    val b1 = head[1].toInt() and 0xFF
    return when {
        b0 == 0x1F && b1 == 0x8B -> "gzip"                              // tar.gz / .nvrup
        b0 == 0x50 && b1 == 0x4B -> "zip"                               // .zip
        b0 == 0x28 && b1 == 0xB5 -> "zstd"                              // .tar.zst
        else -> null
    }
}

/** Trạng thái được coi là "đang chạy dở" — không nhận việc mới khi đang ở các trạng thái này. */
private val BUSY_STATES = setOf(
    "ACQUIRING", "VERIFYING", "GATING", "SNAPSHOT", "LOADING", "SWAPPING",
    "HEALTH_CHECK", "ROLLING_BACK",
)

private fun currentState(): String =
    runCatching {
        Json.parseToJsonElement(OtaPaths.statusFile.readText())
            .jsonObject["state"]?.jsonPrimitive?.content
    }.getOrNull() ?: "IDLE"

private fun writeRequest(action: String, filename: String?, trigger: String): String {
    val requestId = "req-${Instant.now().toEpochMilli()}"
    val payload = buildJsonObject {
        put("requestId", requestId)
        put("action", action)
        put("trigger", trigger)
        put("requestedAt", Instant.now().toString())
        if (filename != null) put("file", filename)
    }
    OtaPaths.ensureDirs()
    // temp + rename: watchdog có thể đang đọc đúng lúc mình ghi. Rename là atomic trên cùng
    // filesystem nên nó thấy hoặc file cũ hoặc file mới, không bao giờ thấy nửa file.
    val tmp = File(OtaPaths.root, "request.json.tmp")
    tmp.writeText(jsonOut.encodeToString(JsonObject.serializer(), payload))
    if (!tmp.renameTo(OtaPaths.requestFile)) {
        tmp.copyTo(OtaPaths.requestFile, overwrite = true); tmp.delete()
    }
    return requestId
}

fun Route.otaRouting() {

    /**
     * Nhận file gói. Body là RAW BYTES của file (không multipart).
     *
     *   POST /api/ota/upload?filename=nvr-AIVIEW-NVR-X1-1.1.0.0.nvrup
     */
    post("/api/ota/upload") {
        val filename = sanitizeFilename(call.request.queryParameters["filename"])
        if (filename == null) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "OTA_UPLOAD", "BAD_FILENAME",
                "filename=${call.request.queryParameters["filename"]}")
            return@post call.respond(HttpStatusCode.BadRequest, OtaError(
                "Tên file không hợp lệ",
                "Chỉ nhận chữ, số, dấu . _ - và đuôi .nvrup / .tar.gz / .zip"))
        }

        // Không nhận việc mới khi đang apply dở — nếu không, file bị ghi đè giữa lúc watchdog
        // đang đọc nó, và đó là loại lỗi rất khó lần ra từ log.
        val state = currentState()
        if (state in BUSY_STATES) {
            return@post call.respond(HttpStatusCode.Conflict, OtaError(
                "Đang có một lần cập nhật chạy dở", "Trạng thái hiện tại: $state"))
        }

        // Trần kích thước: chặn TRƯỚC khi đọc byte nào, dựa vào Content-Length.
        val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (declared != null && declared > maxUploadBytes) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, OtaError(
                "Gói quá lớn",
                "${declared / 1_048_576}MB vượt trần ${maxUploadBytes / 1_048_576}MB"))
        }

        OtaPaths.ensureDirs()

        // Cần chỗ cho gói + image giải nén + bản cũ giữ lại để rollback ⇒ hệ số 3.
        val free = OtaPaths.root.usableSpace
        val needed = (declared ?: 0L) * 3
        if (needed > 0 && free < needed) {
            return@post call.respond(HttpStatusCode.InsufficientStorage, OtaError(
                "Không đủ dung lượng",
                "cần ~${needed / 1_048_576}MB (gói + giải nén + bản lùi), còn ${free / 1_048_576}MB"))
        }

        val target = File(OtaPaths.incoming, filename)
        val part = File(OtaPaths.incoming, "$filename.part")
        part.delete()

        val t0 = System.currentTimeMillis()
        AppLogger.info(AppLogger.Module.SYSTEM, "OTA_UPLOAD", "START",
            "file=$filename declared=${declared?.let { it / 1_048_576 } ?: -1}MB free=${free / 1_048_576}MB")

        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            val channel = call.receiveChannel()
            val buf = ByteArray(1 shl 16)
            part.outputStream().buffered(1 shl 16).use { out ->
                while (true) {
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n <= 0) break
                    written += n
                    // Trần kiểm lại theo byte thật: Content-Length là do client khai, có thể nói dối.
                    if (written > maxUploadBytes) {
                        throw IllegalStateException(
                            "vượt trần ${maxUploadBytes / 1_048_576}MB giữa lúc tải (Content-Length khai ${declared ?: -1})")
                    }
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                }
            }
        } catch (e: Exception) {
            part.delete()
            AppLogger.error(AppLogger.Module.SYSTEM, "OTA_UPLOAD", "FAIL",
                "file=$filename written=${written / 1_048_576}MB err=${e.message}", e)
            return@post call.respond(HttpStatusCode.InternalServerError, OtaError(
                "Tải lên thất bại", e.message))
        }

        if (written == 0L) {
            part.delete()
            return@post call.respond(HttpStatusCode.BadRequest, OtaError("File rỗng"))
        }

        val kind = sniffArchive(part)
        if (kind == null) {
            part.delete()
            AppLogger.warn(AppLogger.Module.SYSTEM, "OTA_UPLOAD", "NOT_ARCHIVE",
                "file=$filename size=$written — magic bytes không phải gzip/zip/zstd")
            return@post call.respond(HttpStatusCode.BadRequest, OtaError(
                "File không phải gói nén",
                "Đuôi file nói là gói nhưng nội dung không phải gzip/zip/zstd. Đúng file chưa?"))
        }

        // Rename cuối cùng: watchdog chỉ thấy file khi nó đã đầy đủ. Trong lúc tải nó là *.part
        // nên không có cách nào watchdog bốc một file đang ghi dở.
        target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            return@post call.respond(HttpStatusCode.InternalServerError,
                OtaError("Không lưu được file", "rename thất bại"))
        }

        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val ms = System.currentTimeMillis() - t0
        AppLogger.info(AppLogger.Module.SYSTEM, "OTA_UPLOAD", "OK",
            "file=$filename size=${written / 1_048_576}MB kind=$kind sha256=${sha.take(16)} ttl=${ms}ms")

        call.respond(OtaUploadResult(ok = true, filename = filename, size = written, sha256 = sha))
    }

    /**
     * Trạng thái để UI hiển thị.
     *
     * status.json được TRẢ NGUYÊN VĂN (nhúng thẳng vào response) chứ không parse rồi dựng lại.
     * Chủ ý: watchdog là bên định nghĩa cấu trúc đó, backend chỉ chuyển tiếp — nên watchdog
     * thêm field mới thì UI thấy ngay, không phải sửa cả hai module. Cùng cách nghĩ với việc
     * chỉ có MỘT bộ verify.
     */
    get("/api/ota/status") {
        val statusRaw = runCatching { OtaPaths.statusFile.readText() }.getOrNull()
        val stateRaw = runCatching { OtaPaths.stateFile.readText() }.getOrNull()

        val packages = (OtaPaths.incoming.listFiles { f: File -> f.isFile && !f.name.endsWith(".part") }
            ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { OtaPackageInfo(it.name, it.length(), Instant.ofEpochMilli(it.lastModified()).toString()) }

        val payload = buildJsonObject {
            put("currentVersion", appVersion)
            put("deployVersion", deployVersion)
            put("model", System.getenv("DEVICE_MODEL")?.takeIf { it.isNotBlank() } ?: "")
            put("maxUploadBytes", maxUploadBytes)
            put("freeBytes", OtaPaths.root.let { if (it.exists()) it.usableSpace else File(".").usableSpace })
            put("busy", currentState() in BUSY_STATES)
            // Nguyên văn từ watchdog; null khi chưa có lần cập nhật nào trên máy này.
            put("status", statusRaw?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull)
            put("state", stateRaw?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull)
            put("packages", jsonOut.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(OtaPackageInfo.serializer()), packages))
        }
        call.respondText(payload.toString(), ContentType.Application.Json)
    }

    /** Nhờ watchdog verify + áp một gói đã upload. */
    post("/api/ota/apply") {
        val filename = sanitizeFilename(call.request.queryParameters["filename"])
            ?: return@post call.respond(HttpStatusCode.BadRequest, OtaError("Thiếu hoặc sai filename"))

        val f = File(OtaPaths.incoming, filename)
        if (!f.isFile) {
            return@post call.respond(HttpStatusCode.NotFound,
                OtaError("Không thấy gói $filename", "Upload lại?"))
        }

        val state = currentState()
        if (state in BUSY_STATES) {
            return@post call.respond(HttpStatusCode.Conflict, OtaError(
                "Đang có một lần cập nhật chạy dở", "Trạng thái hiện tại: $state"))
        }

        val id = writeRequest("apply", filename, trigger = "manual_upload")
        AppLogger.info(AppLogger.Module.SYSTEM, "OTA_APPLY", "REQUESTED",
            "file=$filename size=${f.length() / 1_048_576}MB requestId=$id by=web-ui")
        call.respond(OtaAck(true,
            "Đã nhận yêu cầu. Watchdog sẽ xác minh chữ ký rồi áp dụng — theo dõi tiến trình ở đây.", id))
    }

    /** Hỏi server xem có bản mới, không chờ tới 2h sáng. */
    post("/api/ota/check-now") {
        val state = currentState()
        if (state in BUSY_STATES) {
            return@post call.respond(HttpStatusCode.Conflict, OtaError(
                "Đang có một lần cập nhật chạy dở", "Trạng thái hiện tại: $state"))
        }
        val id = writeRequest("check", null, trigger = "manual_check")
        AppLogger.info(AppLogger.Module.SYSTEM, "OTA_CHECK", "REQUESTED", "requestId=$id by=web-ui")
        call.respond(OtaAck(true, "Đang hỏi server xem có bản mới…", id))
    }

    /** Xoá một gói đã upload — máy field ổ nhỏ, gói 233MB nằm lại là vấn đề thật. */
    delete("/api/ota/package") {
        val filename = sanitizeFilename(call.request.queryParameters["filename"])
            ?: return@delete call.respond(HttpStatusCode.BadRequest, OtaError("Thiếu hoặc sai filename"))

        if (currentState() in BUSY_STATES) {
            return@delete call.respond(HttpStatusCode.Conflict,
                OtaError("Đang cập nhật, không xoá gói lúc này"))
        }

        val f = File(OtaPaths.incoming, filename)
        if (!f.isFile) return@delete call.respond(HttpStatusCode.NotFound, OtaError("Không thấy $filename"))
        val mb = f.length() / 1_048_576
        if (!f.delete()) {
            return@delete call.respond(HttpStatusCode.InternalServerError, OtaError("Xoá thất bại"))
        }
        AppLogger.info(AppLogger.Module.SYSTEM, "OTA_PACKAGE", "DELETED", "file=$filename freed=${mb}MB")
        call.respond(OtaAck(true, "Đã xoá $filename (giải phóng ${mb}MB)", ""))
    }
}
