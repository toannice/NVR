package vn.aiview.nvr.cloud

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import vn.aiview.nvr.utils.AppLogger
import java.net.URLEncoder

object Go2RtcClient {
    private const val BASE = "http://127.0.0.1:1984"
    private val http = HttpClient(CIO) { engine { requestTimeout = 5_000 } }

    suspend fun registerStreamSync(name: String, src: String, timeoutMs: Long = 5_000): Boolean {
        val encodedSrc = URLEncoder.encode(src, Charsets.UTF_8)
        val encodedName = URLEncoder.encode(name, Charsets.UTF_8)
        val ok = withTimeoutOrNull(timeoutMs) {
            try {
                val resp: HttpResponse = http.put("$BASE/api/streams?name=$encodedName&src=$encodedSrc")
                resp.status.isSuccess()
            } catch (e: Exception) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "GO2RTC", "REGISTER_FAIL", "$name: ${e.message}")
                false
            }
        } ?: false
        AppLogger.info(AppLogger.Module.SYSTEM, "GO2RTC", "REGISTER", "name=$name ok=$ok")
        return ok
    }

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * GET /api/streams → returns the set of stream names that currently have an ACTIVE producer
     * (a source connection delivering frames). Used by StreamOnlineTracker to derive `online`.
     *
     * go2rtc shape: { "<name>": { "producers": [ {...} ], "consumers": [...] }, ... }
     * "live" = the `producers` array is non-empty (go2rtc only lists a producer once the upstream
     * source is connected). Returns an empty set on any error so callers degrade to "offline".
     */
    suspend fun liveStreamNames(): Set<String> {
        return try {
            val resp: HttpResponse = http.get("$BASE/api/streams")
            if (!resp.status.isSuccess()) return emptySet()
            val body = resp.bodyAsText()
            val root = lenientJson.parseToJsonElement(body).jsonObject
            buildSet {
                for ((name, info) in root) {
                    val producers = info.jsonObject["producers"]?.jsonArray ?: continue
                    if (producers.isNotEmpty()) add(name)
                }
            }
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "GO2RTC", "LIST_FAIL", e.message ?: "")
            emptySet()
        }
    }

    /**
     * Register an MP4 file as a go2rtc stream using the ffmpeg source.
     * go2rtc will use ffmpeg to read the file and serve it as WebRTC.
     * filePath must be accessible inside the container (e.g. /app/media/recordings/...).
     */
    suspend fun registerFfmpegStream(name: String, filePath: String, timeoutMs: Long = 5_000): Boolean {
        // go2rtc runs inside frigate container where recordings are at /media/frigate/recordings/.
        // nvr-wrapper mounts the same dir at /app/media/recordings/ — translate before passing to go2rtc.
        val frigateFilePath = filePath.replace("/app/media/recordings", "/media/frigate/recordings")
        val src = "ffmpeg:$frigateFilePath#video=h264"
        val encodedName = URLEncoder.encode(name, Charsets.UTF_8)
        val encodedSrc = URLEncoder.encode(src, Charsets.UTF_8)
        val ok = withTimeoutOrNull(timeoutMs) {
            try {
                val resp: HttpResponse = http.put("$BASE/api/streams?name=$encodedName&src=$encodedSrc")
                resp.status.isSuccess()
            } catch (e: Exception) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "GO2RTC", "FFMPEG_REGISTER_FAIL",
                    "name=$name file=$filePath err=${e.message}")
                false
            }
        } ?: false
        AppLogger.info(AppLogger.Module.SYSTEM, "GO2RTC", "FFMPEG_REGISTER",
            "name=$name file=$filePath ok=$ok")
        return ok
    }

    /**
     * Register a Frigate HLS VOD stream for WebRTC playback via a real-time MPEG-TS proxy.
     *
     * Flow:
     *   1. PlaybackStreamer spawns `ffmpeg -re -i hlsUrl` — reads at 1x speed, outputs MPEG-TS to stdout.
     *   2. nvr-wrapper serves the output at GET /internal/playback-ts/{name} (accessible via bridge IP).
     *   3. go2rtc registers `ffmpeg:http://{bridgeIp}:3000/internal/playback-ts/{name}` — its internal
     *      ffmpeg reads our MPEG-TS at the pace we deliver it (real-time), yielding correct RTP timestamps.
     *
     * Bridge IP 172.19.0.1 is the host side of the nvr-backend Docker bridge, reachable from Frigate.
     * nvr-wrapper must bind to 0.0.0.0 (not 127.0.0.1) for this to work.
     */
    suspend fun registerHlsStream(name: String, hlsUrl: String, timeoutMs: Long = 15_000): Boolean {
        deleteStream(name)
        vn.aiview.nvr.services.PlaybackStreamer.stop(name)

        val started = vn.aiview.nvr.services.PlaybackStreamer.start(name, hlsUrl)
        if (!started) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "GO2RTC", "HLS_REGISTER_NO_FFMPEG",
                "name=$name PlaybackStreamer.start failed")
            return false
        }

        // Brief wait for ffmpeg to open the HLS and start writing output
        kotlinx.coroutines.delay(800)

        val proxyUrl = "http://172.19.0.1:3000/internal/playback-ts/$name"
        // Register as ffmpeg: source so go2rtc uses its ffmpeg to demux the MPEG-TS.
        val src = "ffmpeg:$proxyUrl#video=copy"
        val encodedName = URLEncoder.encode(name, Charsets.UTF_8)
        val encodedSrc = URLEncoder.encode(src, Charsets.UTF_8)
        val ok = withTimeoutOrNull(timeoutMs) {
            try {
                val resp: HttpResponse = http.put("$BASE/api/streams?name=$encodedName&src=$encodedSrc")
                resp.status.isSuccess()
            } catch (e: Exception) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "GO2RTC", "HLS_REGISTER_FAIL",
                    "name=$name err=${e.message}")
                false
            }
        } ?: false

        AppLogger.info(AppLogger.Module.SYSTEM, "GO2RTC", "HLS_REGISTER",
            "name=$name ok=$ok src=$src hlsUrl=$hlsUrl")
        if (!ok) {
            vn.aiview.nvr.services.PlaybackStreamer.stop(name)
        }
        return ok
    }

    suspend fun deleteStream(name: String) {
        val encodedName = URLEncoder.encode(name, Charsets.UTF_8)
        try {
            http.delete("$BASE/api/streams?src=$encodedName")
        } catch (_: Exception) {
        }
    }

    /** Delete a playback stream by name and stop the MPEG-TS subprocess. */
    suspend fun deletePlaybackStream(name: String) {
        AppLogger.info(AppLogger.Module.SYSTEM, "GO2RTC", "PLAYBACK_DELETE", "stream=$name")
        deleteStream(name)
        vn.aiview.nvr.services.PlaybackStreamer.stop(name)
    }
}
