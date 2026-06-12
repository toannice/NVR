package vn.aiview.nvr.services

import kotlinx.coroutines.*
import vn.aiview.nvr.cloud.Go2RtcClient
import vn.aiview.nvr.utils.AppLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks per-camera `online` status for the AiView Box integration
 * (NVR_AIView_Integration_Protocol v1.1, §5 — semantics + debounce).
 *
 * `online` = the restream `${channelId}_main` in go2rtc currently has an active producer
 * (receiving frames), NOT an ICMP ping. To avoid badge flapping when the network blips:
 *   - false → true : immediately, the moment a producer reappears.
 *   - true  → false : only after the producer has been missing for > OFFLINE_AFTER_MS (10s).
 *
 * A background loop polls go2rtc every POLL_MS and records the last time each channel was seen live.
 */
object StreamOnlineTracker {
    private const val POLL_MS = 2_000L
    private const val OFFLINE_AFTER_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    // channelId -> last time the `${channelId}_main` producer was seen live (epoch ms)
    private val lastSeen = ConcurrentHashMap<String, Long>()

    fun start() {
        if (job != null) return
        AppLogger.info(AppLogger.Module.SYSTEM, "INTEGRATION", "ONLINE_TRACKER_START", "poll=${POLL_MS}ms offlineAfter=${OFFLINE_AFTER_MS}ms")
        job = scope.launch {
            while (isActive) {
                runCatching {
                    val live = Go2RtcClient.liveStreamNames()
                    val now = System.currentTimeMillis()
                    live.forEach { name ->
                        if (name.endsWith("_main")) {
                            lastSeen[name.removeSuffix("_main")] = now
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

    /** Debounced online state for one camera. true if seen live within OFFLINE_AFTER_MS. */
    fun isOnline(channelId: String): Boolean {
        val t = lastSeen[channelId] ?: return false
        return System.currentTimeMillis() - t <= OFFLINE_AFTER_MS
    }
}
