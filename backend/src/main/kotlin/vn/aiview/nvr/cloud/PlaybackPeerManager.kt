package vn.aiview.nvr.cloud

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import vn.aiview.nvr.utils.AppLogger

/**
 * Client to the dc-bridge PLAYBACK endpoints.
 *
 * The cloud sends playback control (post_select_record / post_control_record) over a
 * DataChannel that rides on the PLAYBACK media peer itself — go2rtc swallows that channel,
 * so dc-bridge (Pion) owns the playback peer instead. dc-bridge serves an H264 track fed by
 * ffmpeg (copy → RTP/UDP) and forwards the DataChannel to the JVM (/internal/dc/command).
 *
 * Bar switch / seek = [swapSource]: dc-bridge restarts ffmpeg with a new src on the SAME peer.
 */
object PlaybackPeerManager {
    private const val BASE = "http://127.0.0.1:8766"
    private val client = HttpClient(CIO) { engine { requestTimeout = 15_000 } }

    /** Negotiate the playback peer; returns the answer SDP or null on failure. */
    suspend fun handleOffer(sdp: String, srcUrl: String, iceServers: List<IceServer>): String? {
        return try {
            val body = buildJsonObject {
                put("sdp", sdp)
                put("src_url", srcUrl)
                put("ice_servers", buildJsonArray {
                    iceServers.forEach { ice ->
                        addJsonObject {
                            put("urls", buildJsonArray { ice.urls.forEach { add(it) } })
                            if (ice.username != null) put("username", ice.username)
                            if (ice.credential != null) put("credential", ice.credential)
                        }
                    }
                })
            }
            val response = client.post("$BASE/playback/offer") {
                header("Content-Type", "application/json")
                setBody(body.toString())
            }
            if (!response.status.isSuccess()) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "PLAYBACK_OFFER_FAIL",
                    "dc-bridge ${response.status.value}: ${response.bodyAsText().take(200)}")
                return null
            }
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["sdp"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "PLAYBACK_OFFER_FAIL", e.message ?: "")
            null
        }
    }

    /** Swap the ffmpeg source on the active playback peer (bar switch / seek). */
    suspend fun swapSource(srcUrl: String): Boolean {
        return try {
            val response = client.post("$BASE/playback/source") {
                header("Content-Type", "application/json")
                setBody(buildJsonObject { put("src_url", srcUrl) }.toString())
            }
            if (!response.status.isSuccess()) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "SWAP_SOURCE_FAIL",
                    "dc-bridge ${response.status.value}: ${response.bodyAsText().take(200)}")
                return false
            }
            true
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK", "SWAP_SOURCE_FAIL", e.message ?: "")
            false
        }
    }

    /** Tear down the active playback peer + ffmpeg. */
    suspend fun stop() {
        try {
            client.post("$BASE/playback/stop")
        } catch (e: Exception) {
            AppLogger.debug(AppLogger.Module.SYSTEM, "PLAYBACK", "STOP_FAIL", e.message ?: "")
        }
    }
}
