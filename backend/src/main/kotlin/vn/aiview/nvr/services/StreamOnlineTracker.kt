package vn.aiview.nvr.services

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.utils.AppLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks per-camera `online` status for the AiView Box integration.
 *
 * `online` = Frigate reports camera_fps > 0 for the camera's slug (frames actually arriving
 * from the physical camera). Previously used go2rtc producers, but go2rtc always lists a
 * producer for every registered stream (even when the upstream camera is offline) because
 * streams point to Frigate's internal RTSP which accepts connections regardless.
 *
 * Debounce: false→true immediately; true→false only after OFFLINE_AFTER_MS (10s).
 */
object StreamOnlineTracker {
    private const val POLL_MS = 2_000L
    private const val OFFLINE_AFTER_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val http = HttpClient(CIO) { engine { requestTimeout = 5_000 } }
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    // channelId -> last epoch ms when Frigate reported camera_fps > 0
    private val lastSeen = ConcurrentHashMap<String, Long>()

    fun start() {
        if (job != null) return
        AppLogger.info(AppLogger.Module.SYSTEM, "INTEGRATION", "ONLINE_TRACKER_START", "poll=${POLL_MS}ms offlineAfter=${OFFLINE_AFTER_MS}ms source=frigate_stats")
        job = scope.launch {
            while (isActive) {
                runCatching {
                    val channelToSlug: Map<String, String> = transaction {
                        Cameras.selectAll().mapNotNull { row ->
                            val ch = row[Cameras.channelId] ?: return@mapNotNull null
                            ch to row[Cameras.slug]
                        }.toMap()
                    }

                    val slugFps = frigateSlugFps()
                    val now = System.currentTimeMillis()

                    for ((channelId, slug) in channelToSlug) {
                        if ((slugFps[slug] ?: 0.0) > 0.0) {
                            lastSeen[channelId] = now
                        }
                    }
                }.onFailure {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "INTEGRATION", "ONLINE_TRACKER_ERR", it.message ?: "")
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** Debounced online state for one camera. */
    fun isOnline(channelId: String): Boolean {
        val t = lastSeen[channelId] ?: return false
        return System.currentTimeMillis() - t <= OFFLINE_AFTER_MS
    }

    /** GET Frigate /api/stats → map of slug → camera_fps. */
    private suspend fun frigateSlugFps(): Map<String, Double> {
        return try {
            val resp = http.get("http://127.0.0.1:5000/api/stats")
            if (!resp.status.value.toString().startsWith("2")) return emptyMap()
            val root = lenientJson.parseToJsonElement(resp.bodyAsText()).jsonObject
            val cameras = root["cameras"]?.jsonObject ?: return emptyMap()
            buildMap {
                for ((slug, info) in cameras) {
                    val fps = info.jsonObject["camera_fps"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    put(slug, fps)
                }
            }
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "INTEGRATION", "FRIGATE_STATS_FAIL", e.message ?: "")
            emptyMap()
        }
    }
}
