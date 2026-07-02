package vn.aiview.nvr.cloud.handlers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import vn.aiview.nvr.cloud.CommandDispatcher
import vn.aiview.nvr.cloud.PlaybackPeerManager
import vn.aiview.nvr.services.PlaybackService
import vn.aiview.nvr.utils.AppLogger

object PlaybackCommandHandler {
    /**
     * Last-known token per channelId (MVP: one playback session per channel).
     * The cloud's control commands ride the PLAYBACK peer's DataChannel and don't always
     * carry a token, so we remember the last one to resolve seek. Keys also serve as the
     * set of channels with an active playback peer in dc-bridge.
     */
    private val activePlaybackTokens = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Position/speed bookkeeping for the active playback run, so a bare `scale`
     * command (which carries no position) can restart ffmpeg at the CURRENT media
     * position with the new speed. [startSec] is where the current ffmpeg run began,
     * [wallStartMs] when it began; current position ≈ startSec + elapsed·rate.
     */
    private data class PlayPos(
        val slug: String,
        val startSec: Long,
        val endSec: Long,
        val wallStartMs: Long,
        val rate: Double,
    )
    private val playPos = java.util.concurrent.ConcurrentHashMap<String, PlayPos>()

    private fun setPlayPos(channelId: String, slug: String, startSec: Long, endSec: Long, rate: Double) {
        playPos[channelId] = PlayPos(slug, startSec, endSec, System.currentTimeMillis(), rate)
    }

    private fun currentRate(channelId: String): Double = playPos[channelId]?.rate ?: 1.0

    /** Estimated current media position (seconds) of the running ffmpeg, clamped to the range. */
    private fun currentMediaSec(channelId: String): Long? {
        val st = playPos[channelId] ?: return null
        val elapsed = ((System.currentTimeMillis() - st.wallStartMs) / 1000.0) * st.rate
        return (st.startSec + elapsed.toLong()).coerceIn(st.startSec, st.endSec)
    }

    /** Called by SignalingManager when a PLAYBACK peer is established for the initial token. */
    fun onPlaybackStarted(channelId: String, token: String) {
        activePlaybackTokens[channelId] = token
        PlaybackService.parseToken(token)?.let { p ->
            PlaybackService.channelToSlug(p.channelId)?.let { slug ->
                setPlayPos(channelId, slug, p.startSec, p.endSec, 1.0)
            }
        }
        AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "PLAYBACK_STARTED",
            "channelId=$channelId token=$token")
    }

    /**
     * Cloud sends either channel_id (numeric string) or sourceToken (ONVIF profile token).
     * Resolve both to a channelId for PlaybackService lookups.
     */
    private fun resolveChannelId(data: JsonObject): String? {
        data["channel_id"]?.jsonPrimitive?.content?.takeIf(String::isNotEmpty)?.let { return it }
        val sourceToken = data["sourceToken"]?.jsonPrimitive?.content?.takeIf(String::isNotEmpty)
            ?: return null
        val resolved = PlaybackService.sourceTokenToChannelId(sourceToken)
        if (resolved == null) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "RESOLVE_CHANNEL_FAIL",
                "sourceToken=$sourceToken not found in cameras table")
        } else {
            AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "RESOLVE_CHANNEL_OK",
                "sourceToken=$sourceToken → channelId=$resolved")
        }
        return resolved
    }

    fun handle(func: String, data: JsonObject): JsonObject = when (func) {
        "get_recording_recordingjob"             -> getRecordingJob(data)
        "get_search_findrecordings",
        "post_search_findrecordings"             -> findRecordings(data)
        "post_search_calendar"                   -> searchCalendar(data)
        "post_select_record",
        "post_select_record_timestamp"           -> selectRecord(data)
        "post_control_record"                    -> controlRecord(data)
        else -> CommandDispatcher.errorResponse("E0008", "Function not supported: $func")
    }

    // ── get_recording_recordingjob ────────────────────────────────────────────

    private fun getRecordingJob(data: JsonObject): JsonObject {
        AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "GET_RECORDING_JOB", "data=$data")
        // Return a placeholder job — Frigate always records based on its config.
        return CommandDispatcher.successResponse(buildJsonObject {
            putJsonArray("recordingjobs") {
                addJsonObject {
                    put("token", "job_default")
                    put("mode", "Continuous")
                }
            }
        })
    }

    // ── post_search_calendar ──────────────────────────────────────────────────

    private fun searchCalendar(data: JsonObject): JsonObject {
        val channelId = resolveChannelId(data)
        AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "SEARCH_CALENDAR_REQ",
            "channelId=$channelId")
        val dates = PlaybackService.getCalendar(channelId)
        AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "SEARCH_CALENDAR_OK",
            "channelId=$channelId dates=$dates")
        return CommandDispatcher.successResponse(buildJsonObject {
            putJsonArray("dates") { dates.forEach { add(JsonPrimitive(it)) } }
        })
    }

    // ── get/post_search_findrecordings ────────────────────────────────────────

    private fun findRecordings(data: JsonObject): JsonObject {
        val channelId = resolveChannelId(data)
            ?: return CommandDispatcher.errorResponse("E0007", "Missing channel_id or unknown sourceToken")
        // Echo back the same sourceToken the cloud sent (for matching on cloud side)
        val requestSourceToken = data["sourceToken"]?.jsonPrimitive?.contentOrNull
            ?: data["channel_id"]?.jsonPrimitive?.contentOrNull
        // Cloud sends timestamps in ms (timeRange.startTime); flat after/before are also accepted in seconds.
        val timeRange = data["timeRange"]?.jsonObject
        val afterSec = (timeRange?.get("startTime")?.jsonPrimitive?.doubleOrNull?.toLong()?.let { it / 1000 })
            ?: data["after"]?.jsonPrimitive?.doubleOrNull?.toLong()
            ?: data["start_time"]?.jsonPrimitive?.doubleOrNull?.toLong()
            ?: 0L
        val beforeSec = (timeRange?.get("endTime")?.jsonPrimitive?.doubleOrNull?.toLong()?.let { it / 1000 })
            ?: data["before"]?.jsonPrimitive?.doubleOrNull?.toLong()
            ?: data["end_time"]?.jsonPrimitive?.doubleOrNull?.toLong()
            ?: (System.currentTimeMillis() / 1000)
        AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "FIND_RECORDINGS_REQ",
            "channelId=$channelId requestSourceToken=$requestSourceToken after=$afterSec before=$beforeSec")
        val recordings = PlaybackService.findRecordings(channelId, afterSec, beforeSec, requestSourceToken)
        AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "FIND_RECORDINGS_OK",
            "channelId=$channelId count=${recordings.size}")
        return CommandDispatcher.successResponse(buildJsonObject {
            // B01 protocol: cloud reads "recordingFiles" array for timeline bar rendering
            putJsonArray("recordingFiles") { recordings.forEach { add(it) } }
        })
    }

    // ── post_select_record ────────────────────────────────────────────────────

    private fun selectRecord(data: JsonObject): JsonObject {
        val token = data["token"]?.jsonPrimitive?.content
            ?: return CommandDispatcher.errorResponse("E0007", "Missing token")
        val seekSec = data["seek"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
        AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "SELECT_RECORD_REQ",
            "token=$token seek=${seekSec}s")

        val parsed = PlaybackService.parseToken(token)
            ?: return CommandDispatcher.errorResponse("E0007", "Invalid recording token: $token")

        val adjustedStartSec = parsed.startSec + seekSec
        val slug = PlaybackService.channelToSlug(parsed.channelId)
            ?: return CommandDispatcher.errorResponse("E0013",
                "Camera not found for channelId=${parsed.channelId}")

        val vodUrl = PlaybackService.vodUrl(slug, adjustedStartSec, parsed.endSec)
        val rate = currentRate(parsed.channelId)
        return runBlocking {
            // Swap the ffmpeg source on the live playback peer (no renegotiation) — this is
            // the bar-switch path. dc-bridge keeps the same WebRTC track, so video continues.
            // Preserve the current speed so a bar jump while fast-forwarding stays fast.
            val ok = PlaybackPeerManager.swapSource(vodUrl, rate)
            if (!ok) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "SELECT_RECORD_SWAP_FAIL",
                    "vodUrl=$vodUrl — no active playback peer? (post_select_record before PLAYBACK offer)")
                return@runBlocking CommandDispatcher.errorResponse("E0011",
                    "No active playback peer to switch — open a PLAYBACK session first")
            }
            activePlaybackTokens[parsed.channelId] = token
            setPlayPos(parsed.channelId, slug, adjustedStartSec, parsed.endSec, rate)
            AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "SELECT_RECORD_OK",
                "channelId=${parsed.channelId} vodUrl=$vodUrl")
            CommandDispatcher.successResponse(buildJsonObject {
                put("token", token)
                put("status", "ok")
            })
        }
    }

    // ── post_control_record ───────────────────────────────────────────────────

    private fun controlRecord(data: JsonObject): JsonObject {
        val action = data["action"]?.jsonPrimitive?.content ?: ""
        val seekSec = data["seek"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
        val channelId = data["channel_id"]?.jsonPrimitive?.content
            ?: activePlaybackTokens.keys.firstOrNull()
            ?: ""
        AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "CONTROL_RECORD_REQ",
            "channelId=$channelId action=$action seek=$seekSec")
        return when (action) {
            "play" -> {
                if (activePlaybackTokens.containsKey(channelId)) {
                    AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "CONTROL_PLAY_OK",
                        "channelId=$channelId already streaming")
                    CommandDispatcher.successResponse(buildJsonObject { put("status", "playing") })
                } else {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "CONTROL_PLAY_NO_STREAM",
                        "channelId=$channelId no active playback session")
                    CommandDispatcher.errorResponse("E0003", "No active playback session")
                }
            }
            "seek" -> {
                // Cloud may or may not re-send the token; fall back to last-known token for the channel.
                val token = data["token"]?.jsonPrimitive?.content
                    ?: activePlaybackTokens[channelId]
                if (token != null) {
                    val parsed = PlaybackService.parseToken(token)
                        ?: return CommandDispatcher.errorResponse("E0007", "Invalid token")
                    val slug = PlaybackService.channelToSlug(parsed.channelId)
                        ?: return CommandDispatcher.errorResponse("E0013", "Camera not found")
                    val adjustedStart = parsed.startSec + seekSec
                    val newVodUrl = PlaybackService.vodUrl(slug, adjustedStart, parsed.endSec)
                    val rate = currentRate(parsed.channelId)
                    AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "CONTROL_SEEK",
                        "channelId=${parsed.channelId} seekSec=$seekSec adjustedStart=$adjustedStart rate=${rate}x vodUrl=$newVodUrl")
                    // Swap the ffmpeg source on the live playback peer at the seeked position.
                    val ok = runBlocking { PlaybackPeerManager.swapSource(newVodUrl, rate) }
                    if (!ok) return CommandDispatcher.errorResponse("E0011", "No active playback peer to seek")
                    setPlayPos(parsed.channelId, slug, adjustedStart, parsed.endSec, rate)
                    CommandDispatcher.successResponse(buildJsonObject { put("status", "seeking") })
                } else {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "CONTROL_SEEK_NO_TOKEN",
                        "channelId=$channelId seek=$seekSec no token and no active session")
                    CommandDispatcher.errorResponse("E0007", "No active playback session for seek")
                }
            }
            "pause" -> {
                AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "CONTROL_PAUSE_NOOP",
                    "channelId=$channelId pause not supported — acknowledging")
                CommandDispatcher.successResponse(buildJsonObject { put("status", "paused") })
            }
            "scale" -> {
                val scale = data["scale"]?.jsonPrimitive?.doubleOrNull?.takeIf { it >= 1.0 } ?: 1.0
                val st = playPos[channelId]
                if (st == null) {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "CONTROL_SCALE_NO_SESSION",
                        "channelId=$channelId scale=$scale — no active playback session")
                    return CommandDispatcher.errorResponse("E0003", "No active playback session")
                }
                // Bare scale carries no position → resume from the current media position at
                // the new speed. dc-bridge fast-forwards via -readrate + setpts (real-time
                // playout, content advances scale×).
                val pos = currentMediaSec(channelId) ?: st.startSec
                val newVodUrl = PlaybackService.vodUrl(st.slug, pos, st.endSec)
                AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "CONTROL_SCALE",
                    "channelId=$channelId scale=${scale}x pos=$pos vodUrl=$newVodUrl")
                val ok = runBlocking { PlaybackPeerManager.swapSource(newVodUrl, scale) }
                if (!ok) return CommandDispatcher.errorResponse("E0011", "No active playback peer to scale")
                setPlayPos(channelId, st.slug, pos, st.endSec, scale)
                CommandDispatcher.successResponse(buildJsonObject { put("scale", scale) })
            }
            else -> {
                AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "CONTROL_UNKNOWN",
                    "channelId=$channelId action=$action")
                CommandDispatcher.errorResponse("E0008", "Unknown action: $action")
            }
        }
    }

    /** Called when a PLAYBACK session ends — tear down the dc-bridge playback peer + ffmpeg. */
    fun cleanupPlaybackStream(channelId: String) {
        if (activePlaybackTokens.remove(channelId) == null) return
        playPos.remove(channelId)
        AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK", "CLEANUP_STREAM",
            "channelId=$channelId")
        runBlocking { PlaybackPeerManager.stop() }
    }
}
