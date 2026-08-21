package vn.aiview.nvr.services

import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.utils.AppLogger
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object PlaybackService {
    private const val RECORDINGS_ROOT = "/app/media/recordings"
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // ── Token helpers ──────────────────────────────────────────────────────────

    /** Token format: "{channelId}:{startSec}:{endSec}" — self-contained, no DB lookup needed. */
    fun buildToken(channelId: String, startSec: Long, endSec: Long): String =
        "$channelId:$startSec:$endSec"

    data class RecordingToken(val channelId: String, val startSec: Long, val endSec: Long)

    fun parseToken(token: String): RecordingToken? {
        val parts = token.split(":")
        if (parts.size < 3) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "TOKEN_PARSE_FAIL",
                "token='$token' expected format channelId:startSec:endSec")
            return null
        }
        return try {
            RecordingToken(parts[0], parts[1].toLong(), parts[2].toLong())
        } catch (e: NumberFormatException) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "TOKEN_PARSE_NUM_FAIL",
                "token='$token' err=${e.message}")
            null
        }
    }

    /** Generate a unique go2rtc stream name for a playback session. */
    fun playbackStreamName(channelId: String, startSec: Long): String =
        "playback_${channelId}_$startSec"

    /** Frigate HLS VOD playlist URL for a recording window (continuous segment playback). */
    fun vodUrl(slug: String, startSec: Long, endSec: Long): String =
        "http://127.0.0.1:5000/vod/$slug/start/$startSec/end/$endSec/index.m3u8"

    // ── DB helpers ─────────────────────────────────────────────────────────────

    /** Map channelId → camera slug (Frigate camera name = recording directory name). */
    fun channelToSlug(channelId: String): String? = try {
        transaction {
            Cameras.selectAll().where { Cameras.channelId eq channelId }
                .firstOrNull()?.get(Cameras.slug)
        }
    } catch (e: Exception) {
        AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "CHANNEL_TO_SLUG_FAIL",
            "channelId=$channelId err=${e.message}")
        null
    }

    /** Map ONVIF sourceToken (main_token or sub_token) → channelId. Cloud sends sourceToken, not channel_id. */
    fun sourceTokenToChannelId(sourceToken: String): String? = try {
        transaction {
            Cameras.selectAll()
                .where { (Cameras.mainToken eq sourceToken) or (Cameras.subToken eq sourceToken) }
                .firstOrNull()?.get(Cameras.channelId)
        }
    } catch (e: Exception) {
        AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "SOURCE_TOKEN_LOOKUP_FAIL",
            "sourceToken=$sourceToken err=${e.message}")
        null
    }

    // ── Recording jobs ─────────────────────────────────────────────────────────

    data class RecordingJob(val jobToken: String, val mode: String, val profileToken: String)

    /**
     * One recording job per media profile, B01-style. Frigate records the MAIN stream of every
     * configured camera continuously, so main profiles are "Active" and sub profiles "Idle".
     */
    fun listRecordingJobs(): List<RecordingJob> = try {
        transaction {
            Cameras.selectAll().mapNotNull { row ->
                val chId = row[Cameras.channelId] ?: return@mapNotNull null
                val main = row[Cameras.mainToken].takeIf { it.isNotBlank() }
                val sub = row[Cameras.subToken].takeIf { it.isNotBlank() }
                listOfNotNull(
                    main?.let { RecordingJob("RecordingJob_${chId}01", "Active", it) },
                    sub?.let { RecordingJob("RecordingJob_${chId}02", "Idle", it) },
                )
            }.flatten()
        }
    } catch (e: Exception) {
        AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "RECORDING_JOBS_FAIL", e.message ?: "")
        emptyList()
    }

    // ── Calendar ───────────────────────────────────────────────────────────────

    /**
     * Return list of dates (YYYY-MM-DD) that have recordings for a given camera channel.
     * If channelId is null, returns dates that have recordings for ANY camera.
     *
     * Recording dirs on disk are UTC (`YYYY-MM-DD/HH/{slug}/MM.SS.mp4`) but the cloud UI shows
     * the operator's LOCAL calendar, so every hour bucket is shifted by [offsetMs] (the cloud
     * sends its tz offset in ms; defaults to the device tz) before the date is taken. Without
     * this, recordings between 00:00 and 07:00 local land on the previous day's calendar entry.
     *
     * [startMs]/[endMs] (epoch ms, already local-agnostic) narrow the range when the cloud asks
     * for a window; 0 means "unbounded".
     */
    fun getCalendar(
        channelId: String?,
        offsetMs: Long = defaultOffsetMs(),
        startMs: Long = 0L,
        endMs: Long = 0L,
    ): List<String> {
        return try {
            val slug = channelId?.let {
                channelToSlug(it) ?: run {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "CALENDAR_NO_SLUG",
                        "channelId=$channelId not found in DB — returning empty calendar")
                    return emptyList()
                }
            }
            val root = File(RECORDINGS_ROOT)
            if (!root.exists()) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "CALENDAR_NO_ROOT",
                    "$RECORDINGS_ROOT does not exist")
                return emptyList()
            }

            val dates = mutableSetOf<String>()
            root.listFiles()
                ?.filter { it.isDirectory && it.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                ?.forEach { dateDir ->
                    dateDir.listFiles()
                        ?.filter { it.isDirectory && it.name.matches(Regex("\\d{2}")) }
                        ?.forEach { hourDir ->
                            val hasRecording = if (slug != null) {
                                File(hourDir, slug).listFiles { f -> f.extension == "mp4" }
                                    ?.isNotEmpty() == true
                            } else {
                                hourDir.walkTopDown().any { it.extension == "mp4" }
                            }
                            if (!hasRecording) return@forEach
                            val hourStartSec = mp4ToEpochSec(dateDir.name, hourDir.name, "00.00")
                                ?: return@forEach
                            val hourMs = hourStartSec * 1000L
                            // Keep the hour if it can overlap the requested window at all.
                            if (startMs > 0 && hourMs + 3_600_000L <= startMs) return@forEach
                            if (endMs > 0 && hourMs > endMs) return@forEach
                            dates.add(localDate(hourMs, offsetMs))
                        }
                }

            val sorted = dates.sorted().reversed()
            AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "CALENDAR_OK",
                "channelId=$channelId slug=$slug offsetMs=$offsetMs dates=${sorted.size}")
            sorted
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "CALENDAR_FAIL", e.message ?: "")
            emptyList()
        }
    }

    /** Device timezone offset in ms — fallback when the cloud omits `offset`. */
    fun defaultOffsetMs(): Long =
        java.time.ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds * 1000L

    /** epoch ms + tz offset → "YYYY-MM-DD" (the date the operator sees). */
    private fun localDate(epochMs: Long, offsetMs: Long): String =
        Instant.ofEpochMilli(epochMs + offsetMs).atZone(ZoneOffset.UTC).toLocalDate().toString()

    // ── Recording search ───────────────────────────────────────────────────────

    /**
     * Scan the recordings filesystem for segments within [afterSec, beforeSec].
     * Frigate 0.16.3's /recordings API is unavailable (no DB table); we read from disk directly.
     * Path pattern: RECORDINGS_ROOT/YYYY-MM-DD/HH/{slug}/MM.SS.mp4
     */
    fun findRecordings(channelId: String, afterSec: Long, beforeSec: Long, overrideSourceToken: String? = null): List<JsonObject> {
        data class CameraInfo(val slug: String, val mainToken: String)
        val cam = try {
            transaction {
                Cameras.selectAll().where { Cameras.channelId eq channelId }.firstOrNull()
                    ?.let { CameraInfo(it[Cameras.slug], it[Cameras.mainToken]) }
            }
        } catch (e: Exception) { null }
        if (cam == null) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "FIND_NO_SLUG",
                "channelId=$channelId not found in DB")
            return emptyList()
        }
        val slug = cam.slug
        // Echo back the same sourceToken the cloud sent so it can match recordings to the camera
        val sourceToken = overrideSourceToken ?: cam.mainToken
        AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "FIND_RECORDINGS_REQ",
            "channelId=$channelId slug=$slug after=$afterSec before=$beforeSec")
        return try {
            val root = File(RECORDINGS_ROOT)
            if (!root.exists()) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "FIND_RECORDINGS_NO_ROOT",
                    "$RECORDINGS_ROOT missing")
                return emptyList()
            }

            val segments = mutableListOf<Pair<Long, File>>() // (startSec, file)
            root.listFiles()
                ?.filter { it.isDirectory && it.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                ?.sortedBy { it.name }
                ?.forEach { dateDir ->
                    dateDir.listFiles()
                        ?.filter { it.isDirectory && it.name.matches(Regex("\\d{2}")) }
                        ?.sortedBy { it.name }
                        ?.forEach { hourDir ->
                            val slugDir = File(hourDir, slug)
                            if (!slugDir.exists()) return@forEach
                            slugDir.listFiles { f -> f.extension == "mp4" }
                                ?.sortedBy { it.name }
                                ?.forEach { mp4 ->
                                    val startSec = mp4ToEpochSec(dateDir.name, hourDir.name, mp4.nameWithoutExtension)
                                        ?: return@forEach
                                    if (startSec in afterSec..beforeSec) {
                                        segments.add(Pair(startSec, mp4))
                                    }
                                }
                        }
                }

            if (segments.isEmpty()) {
                AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "FIND_RECORDINGS_EMPTY",
                    "channelId=$channelId slug=$slug no segments found")
                return emptyList()
            }

            // Group consecutive segments into sessions separated by gaps > 120 seconds.
            // Each group becomes one timeline bar on the cloud UI.
            // Cap each group at 1000 segments — Frigate VOD 503s when >~1080 recordings in range.
            val GAP_THRESHOLD_SEC = 120L
            val sorted = segments.sortedBy { it.first }
            val groups = mutableListOf<List<Pair<Long, File>>>()
            val current = mutableListOf<Pair<Long, File>>()
            var prevSec = -1L
            for (seg in sorted) {
                if (prevSec >= 0 && (seg.first - prevSec) > GAP_THRESHOLD_SEC) {
                    if (current.isNotEmpty()) { groups.add(current.toList()); current.clear() }
                }
                current.add(seg)
                prevSec = seg.first
            }
            if (current.isNotEmpty()) groups.add(current.toList())

            val results = groups.map { group ->
                val limited = group.take(1000)
                val gFirst = limited.first().first
                val gLast = limited.last().first + 15
                buildJsonObject {
                    put("token", buildToken(channelId, gFirst, gLast))
                    put("channel_id", channelId)
                    put("sourceToken", sourceToken)
                    put("profileToken", sourceToken)
                    put("earliestRecording", gFirst * 1000L)
                    put("latestRecording", gLast * 1000L)
                    put("startTime", gFirst * 1000L)
                    put("endTime", gLast * 1000L)
                    put("start_time", gFirst * 1000L)
                    put("end_time", gLast * 1000L)
                    put("duration", (gLast - gFirst) * 1000L)
                    put("pathFile", findMp4PathBySlug(slug, gFirst) ?: "")
                }
            }
            AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "FIND_RECORDINGS_OK",
                "channelId=$channelId slug=$slug segments=${segments.size} sessions=${results.size}")
            results
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "FIND_RECORDINGS_FAIL",
                "channelId=$channelId slug=$slug err=${e.message}")
            emptyList()
        }
    }

    /** Parse "YYYY-MM-DD", "HH", "MM.SS" → Unix epoch seconds (UTC). Returns null on parse error. */
    private fun mp4ToEpochSec(date: String, hour: String, minuteSecond: String): Long? {
        return try {
            val parts = minuteSecond.split(".")
            if (parts.size != 2) return null
            val minute = parts[0].padStart(2, '0')
            val second = parts[1].padStart(2, '0')
            val dt = LocalDateTime.parse("$date $hour:$minute:$second", dateFmt)
            dt.toEpochSecond(ZoneOffset.UTC)
        } catch (_: Exception) {
            null
        }
    }

    // ── MP4 path resolution ────────────────────────────────────────────────────

    /**
     * Find the MP4 file path for a given channelId + startSec.
     * First tries exact timestamp match; if not found, scans for nearest file in the hour directory.
     */
    fun findMp4Path(channelId: String, startSec: Long): String? {
        val slug = channelToSlug(channelId) ?: run {
            AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "FIND_MP4_NO_SLUG",
                "channelId=$channelId")
            return null
        }
        return findMp4PathBySlug(slug, startSec)
    }

    fun findMp4PathBySlug(slug: String, startSec: Long): String? {
        val instant = Instant.ofEpochSecond(startSec).atZone(ZoneOffset.UTC)
        val date = buildString {
            append(instant.year)
            append("-")
            append(instant.monthValue.toString().padStart(2, '0'))
            append("-")
            append(instant.dayOfMonth.toString().padStart(2, '0'))
        }
        val hour = instant.hour.toString().padStart(2, '0')
        val minute = instant.minute.toString().padStart(2, '0')
        val second = instant.second.toString().padStart(2, '0')

        AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "FIND_MP4_ATTEMPT",
            "slug=$slug date=$date hour=$hour target=$minute.$second.mp4")

        val exactFile = File("$RECORDINGS_ROOT/$date/$hour/$slug/$minute.$second.mp4")
        if (exactFile.exists()) {
            AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "FIND_MP4_EXACT", exactFile.absolutePath)
            return exactFile.absolutePath
        }

        // Scan directory for nearest file (Frigate may start segment at slightly different second)
        val dir = File("$RECORDINGS_ROOT/$date/$hour/$slug")
        if (!dir.exists()) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "FIND_MP4_DIR_MISSING",
                "dir=${dir.absolutePath}")
            return null
        }

        val targetSec = instant.minute * 60 + instant.second
        val nearest = dir.listFiles { f -> f.extension == "mp4" }
            ?.mapNotNull { f ->
                val nameParts = f.nameWithoutExtension.split(".")
                if (nameParts.size != 2) return@mapNotNull null
                val m = nameParts[0].toIntOrNull() ?: return@mapNotNull null
                val s = nameParts[1].toIntOrNull() ?: return@mapNotNull null
                val fileSec = m * 60 + s
                // prefer files that start AT or BEFORE the target time
                val delta = targetSec - fileSec
                if (delta >= 0) Pair(f, delta) else null
            }
            ?.minByOrNull { it.second }
            ?.first

        return if (nearest != null) {
            AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "FIND_MP4_NEAREST", nearest.absolutePath)
            nearest.absolutePath
        } else {
            AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "FIND_MP4_NOT_FOUND",
                "slug=$slug date=$date hour=$hour nearestOfTarget=$minute.$second")
            null
        }
    }
}
