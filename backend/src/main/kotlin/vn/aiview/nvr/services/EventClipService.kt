package vn.aiview.nvr.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Schedule clip + snapshot generation for an already-persisted ai_events row.
     * @param snapshotBase64 optional JPEG bytes (base64, with or without a data: URI prefix) from the Box.
     */
    fun enqueue(eventId: String, slug: String, tsMs: Long, snapshotBase64: String? = null) {
        scope.launch {
            try {
                process(eventId, slug, tsMs, snapshotBase64)
            } catch (e: Exception) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "EVENT_CLIP", "PROCESS_FAIL", "id=$eventId err=${e.message}")
                setStatus(eventId, "failed")
            }
        }
    }

    private suspend fun process(eventId: String, slug: String, tsMs: Long, snapshotBase64: String?) {
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

        // 4. Persist paths + status.
        transaction {
            AIEvents.update({ AIEvents.id eq eventId }) {
                it[clipPath] = clipFile.absolutePath
                it[clipStatus] = "ready"
                if (snapOk) it[snapshotPath] = snapFile.absolutePath
            }
        }
        AppLogger.info(AppLogger.Module.SYSTEM, "EVENT_CLIP", "READY",
            "id=$eventId clip=${clipFile.length()}B snapshot=${if (snapOk) "yes" else "no"}")
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
