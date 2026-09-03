package vn.aiview.nvr.services

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import vn.aiview.nvr.models.AIEvents
import vn.aiview.nvr.utils.AppLogger
import java.io.File
import java.util.Base64

/**
 * Cuts a short event clip (fixed -5s / +5s around the event timestamp) from the continuous
 * recordings, and produces a still snapshot for the event list. Runs fully in the background so
 * the Box push request returns immediately (202).
 *
 * Pipeline per event:
 *   1. Wait until the recording covering [ts+POST_SEC] is flushed to disk (Frigate writes ~15s
 *      segments), then some buffer.
 *   2. ffmpeg reads Frigate's VOD HLS window [ts-PRE_SEC, ts+POST_SEC] and stream-copies it to
 *      /app/media/events/{slug}/{eventId}.mp4 (no re-encode).
 *   3. Snapshot: if the Box sent a base64 JPEG, save that; otherwise NVR self-extracts the frame
 *      at the event moment (offset = PRE_SEC into the clip).
 *   4. Update the ai_events row (clip_path, clip_status, snapshot_path).
 */
object EventClipService {

    const val PRE_SEC = 5L
    const val POST_SEC = 5L
    private const val FLUSH_BUFFER_SEC = 20L   // extra wait after ts+POST so the last segment is on disk
    private const val MAX_WAIT_MS = 120_000L   // cap the flush wait — guards against a future-skewed timestamp hanging the job
    private const val EVENTS_ROOT = "/app/media/events"

    /**
     * Tuổi (giây) mà một sự kiện được coi là "còn tươi" — bản ghi của nó có thể CHƯA nằm trên đĩa.
     *
     * Phải lớn hơn FLUSH_BUFFER_SEC cộng biên: Frigate ghi thành từng đoạn, nên ngay sau khi sự kiện
     * xảy ra thì đoạn chứa nó vẫn đang được viết. Hỏi "có bản ghi chưa?" trong cửa sổ này sẽ nhận
     * câu trả lời "chưa" cho MỌI sự kiện tốt — nên trong cửa sổ này ta không hỏi, cứ nhận.
     */
    const val COVERAGE_GRACE_SEC = 30L

    private val coverageClient = HttpClient(CIO) { engine { requestTimeout = 4_000 } }

    /** Một lần hỏi, không thử lại. true = có bản ghi; false = 404 hoặc không nối được. */
    private suspend fun probeOnce(slug: String, startSec: Long, endSec: Long): Boolean = try {
        coverageClient.get(PlaybackService.vodUrl(slug, startSec, endSec)).status.value != 404
    } catch (e: Exception) {
        false
    }

    /** Frigate còn sống không (dù có ghi hình camera này hay không). */
    private suspend fun isFrigateAlive(): Boolean = try {
        coverageClient.get("http://127.0.0.1:5000/api/version").status.value < 500
    } catch (e: Exception) {
        false
    }

    /**
     * Dùng cho sự kiện ĐÃ CŨ: có bản ghi phủ đúng khoảng [startSec, endSec] không?
     *
     * Hỏi đúng cái URL mà lệnh cắt clip sẽ dùng — cùng một nguồn sự thật, nên không bao giờ lệch
     * giữa "ta tưởng có bản ghi" và "ffmpeg đọc được thật".
     *
     * Không nối được cũng tính là KHÔNG có: Frigate với không tới nghĩa là Frigate cũng đang không
     * ghi hình. Thử lại một lần để một cú nấc ngắn không làm mất sự kiện thật.
     */
    suspend fun hasRecording(slug: String, startSec: Long, endSec: Long): Boolean {
        if (probeOnce(slug, startSec, endSec)) return true
        delay(1_000)
        if (probeOnce(slug, startSec, endSec)) return true
        AppLogger.warn(AppLogger.Module.SYSTEM, "EVENT_CLIP", "NO_COVERAGE_PROBE",
            "slug=$slug window=$startSec..$endSec — hỏi 2 lần đều không thấy bản ghi")
        return false
    }

    /**
     * Dùng cho sự kiện CÒN TƯƠI: việc ghi hình camera này có đang chạy không?
     *
     * Không thể hỏi đúng cửa sổ của sự kiện — nó vừa xảy ra, đoạn video còn đang được viết. Nên hỏi
     * một cửa sổ lùi lại 60 giây: nếu việc ghi đang chạy bình thường thì chỗ đó chắc chắn đã có.
     *
     * Nếu chỗ đó trống, còn hai khả năng, và chúng cần cách xử lý ngược nhau:
     *   - NVR vừa khởi động, chưa kịp có 60 giây bản ghi → phải NHẬN, không thì mất sự kiện thật.
     *   - Frigate chết / không ghi → phải LOẠI, không thì lại đẻ ra thẻ "Không có clip".
     * Phân biệt bằng một câu hỏi rẻ: Frigate còn trả lời không.
     */
    suspend fun isRecordingLive(slug: String): Boolean {
        val now = System.currentTimeMillis() / 1000
        if (probeOnce(slug, now - 60, now - 50)) return true
        return isFrigateAlive()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Kích thước luồng chính từng camera (ffprobe) — dò một lần, giữ lại vì hiếm khi đổi. Toạ độ
    // AI camera Bkav gửi lên tính theo luồng chính, cần kích thước thật để quy ra tỉ lệ 0..1.
    private val frameSizeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

    /**
     * Schedule clip + snapshot generation for an already-persisted ai_events row.
     * @param snapshotBase64 optional JPEG bytes (base64, with or without a data: URI prefix) from the Box.
     * @param boxPixel [x, y, w, h] pixel theo khung hình luồng chính, nếu camera có gửi kèm — dùng để
     *   tính box_x/y/w/h chuẩn hoá 0..1 lưu vào ai_events (khoanh vùng đối tượng nhận diện).
     */
    fun enqueue(eventId: String, slug: String, tsMs: Long, snapshotBase64: String? = null, boxPixel: List<Double>? = null) {
        scope.launch {
            try {
                process(eventId, slug, tsMs, snapshotBase64, boxPixel)
            } catch (e: Exception) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "EVENT_CLIP", "PROCESS_FAIL", "id=$eventId err=${e.message}")
                setStatus(eventId, "failed")
            }
        }
    }

    private suspend fun process(eventId: String, slug: String, tsMs: Long, snapshotBase64: String?, boxPixel: List<Double>? = null) {
        val tsSec = tsMs / 1000
        val startSec = tsSec - PRE_SEC
        val endSec = tsSec + POST_SEC

        // 1. Wait until the +POST segment is safely on disk.
        val readyAtMs = (endSec + FLUSH_BUFFER_SEC) * 1000
        val waitMs = (readyAtMs - System.currentTimeMillis()).coerceAtMost(MAX_WAIT_MS)
        if (waitMs > 0) {
            AppLogger.info(AppLogger.Module.SYSTEM, "EVENT_CLIP", "WAIT",
                "id=$eventId waiting ${waitMs}ms for recording to flush")
            delay(waitMs)
        }

        val dir = File("$EVENTS_ROOT/$slug").apply { mkdirs() }
        val clipFile = File(dir, "$eventId.mp4")
        val snapFile = File(dir, "$eventId.jpg")

        // 2. Cut the clip from Frigate VOD (stream copy).
        val vodUrl = PlaybackService.vodUrl(slug, startSec, endSec)
        val clipOk = runFfmpeg(
            "id=$eventId clip",
            listOf(
                "ffmpeg", "-y",
                "-i", vodUrl,
                "-c", "copy",
                "-movflags", "+faststart",
                clipFile.absolutePath
            )
        ) && clipFile.exists() && clipFile.length() > 0

        if (!clipOk) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "EVENT_CLIP", "CLIP_EMPTY",
                "id=$eventId slug=$slug window=$startSec..$endSec — no recording?")
            setStatus(eventId, "failed")
            return
        }

        // 3. Snapshot — prefer the Box-provided image; otherwise self-extract from the clip.
        var snapOk = false
        val decoded = decodeBase64Jpeg(snapshotBase64)
        if (decoded != null) {
            runCatching { snapFile.writeBytes(decoded) }
                .onSuccess {
                    snapOk = true
                    AppLogger.info(AppLogger.Module.SYSTEM, "EVENT_CLIP", "SNAP_BOX", "id=$eventId bytes=${decoded.size}")
                }
                .onFailure { AppLogger.warn(AppLogger.Module.SYSTEM, "EVENT_CLIP", "SNAP_BOX_WRITE_FAIL", "id=$eventId err=${it.message}") }
        }
        if (!snapOk) {
            // Grab the frame at the event moment (PRE_SEC into the -5/+5 clip).
            snapOk = runFfmpeg(
                "id=$eventId snapshot",
                listOf(
                    "ffmpeg", "-y",
                    "-ss", PRE_SEC.toString(),
                    "-i", clipFile.absolutePath,
                    "-frames:v", "1",
                    "-q:v", "2",
                    snapFile.absolutePath
                )
            ) && snapFile.exists() && snapFile.length() > 0
            if (snapOk) AppLogger.info(AppLogger.Module.SYSTEM, "EVENT_CLIP", "SNAP_EXTRACT", "id=$eventId self-extracted")
        }

        // 3b. Chuẩn hoá toạ độ khoanh vùng (nếu camera có gửi) — "tự dò + tự bảo vệ": dò kích thước
        // luồng chính bằng ffprobe, hộp vượt khung thì BỎ VẼ (để null) thay vì vẽ sai.
        val normalizedBox = boxPixel?.let { normalizeBox(slug, it, eventId) }

        // 4. Persist paths + status.
        transaction {
            AIEvents.update({ AIEvents.id eq eventId }) {
                it[clipPath] = clipFile.absolutePath
                it[clipStatus] = "ready"
                if (snapOk) it[snapshotPath] = snapFile.absolutePath
                if (normalizedBox != null) {
                    it[boxX] = normalizedBox[0]
                    it[boxY] = normalizedBox[1]
                    it[boxW] = normalizedBox[2]
                    it[boxH] = normalizedBox[3]
                }
            }
        }
        AppLogger.info(AppLogger.Module.SYSTEM, "EVENT_CLIP", "READY",
            "id=$eventId clip=${clipFile.length()}B snapshot=${if (snapOk) "yes" else "no"}")
    }

    /**
     * [x, y, w, h] pixel → tỉ lệ 0..1 theo kích thước thật luồng chính (ffprobe, dò một lần/camera).
     * Trả null nếu không dò được kích thước, hoặc hộp vượt ra ngoài khung [0, 1] — "tự bảo vệ": thà
     * không vẽ còn hơn vẽ sai vị trí.
     */
    private suspend fun normalizeBox(slug: String, boxPixel: List<Double>, eventId: String): List<Double>? {
        val (fw, fh) = resolveFrameSize(slug) ?: run {
            AppLogger.warn(AppLogger.Module.SYSTEM, "EVENT_CLIP", "BOX_NO_FRAME_SIZE", "id=$eventId slug=$slug")
            return null
        }
        val (px, py, pw, ph) = boxPixel
        val calib = BoxCalibrationService.get(slug)
        val nx = (px / fw) * calib.scaleX + calib.offsetX
        val ny = (py / fh) * calib.scaleY + calib.offsetY
        val nw = (pw / fw) * calib.scaleX
        val nh = (ph / fh) * calib.scaleY
        if (nx < 0 || ny < 0 || nw <= 0 || nh <= 0 || nx + nw > 1.0 || ny + nh > 1.0) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "EVENT_CLIP", "BOX_OUT_OF_FRAME",
                "id=$eventId slug=$slug frame=${fw}x$fh box=$boxPixel norm=[$nx,$ny,$nw,$nh] — bỏ vẽ")
            return null
        }
        return listOf(nx, ny, nw, nh)
    }

    /** Dò kích thước luồng chính qua go2rtc local (rtsp://127.0.0.1:8554/{slug}), có cache. */
    private suspend fun resolveFrameSize(slug: String): Pair<Int, Int>? {
        frameSizeCache[slug]?.let { return it }
        val out = withContext(Dispatchers.IO) {
            runCatching {
                val proc = ProcessBuilder(
                    "ffprobe", "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height",
                    "-of", "csv=s=x:p=0",
                    "rtsp://127.0.0.1:8554/$slug"
                ).redirectErrorStream(false).start()
                val text = proc.inputStream.bufferedReader().readText().trim()
                val finished = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) { proc.destroyForcibly(); return@runCatching null }
                text
            }.getOrNull()
        }
        val parts = out?.split("x")
        if (parts?.size != 2) return null
        val w = parts[0].toIntOrNull()
        val h = parts[1].toIntOrNull()
        if (w == null || h == null || w <= 0 || h <= 0) return null
        val size = w to h
        frameSizeCache[slug] = size
        return size
    }

    private fun setStatus(eventId: String, status: String) = runCatching {
        transaction { AIEvents.update({ AIEvents.id eq eventId }) { it[clipStatus] = status } }
    }

    /** Decode base64 (optionally a `data:image/jpeg;base64,...` URI) into raw bytes; null if blank/invalid. */
    private fun decodeBase64Jpeg(input: String?): ByteArray? {
        val raw = input?.substringAfterLast(",")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Base64.getDecoder().decode(raw) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun runFfmpeg(tag: String, cmd: List<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            val proc = ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val finished = proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                AppLogger.warn(AppLogger.Module.SYSTEM, "EVENT_CLIP", "FFMPEG_TIMEOUT", tag)
                return@withContext false
            }
            val ok = proc.exitValue() == 0
            if (!ok) AppLogger.warn(AppLogger.Module.SYSTEM, "EVENT_CLIP", "FFMPEG_FAIL", "$tag exit=${proc.exitValue()}")
            ok
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "EVENT_CLIP", "FFMPEG_ERR", "$tag err=${e.message}")
            false
        }
    }
}
