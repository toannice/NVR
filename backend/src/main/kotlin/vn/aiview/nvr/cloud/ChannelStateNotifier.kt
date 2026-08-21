package vn.aiview.nvr.cloud

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import vn.aiview.nvr.utils.AppLogger

class ChannelStateNotifier(
    private val publish: (topic: String, payload: String) -> Unit,
    private val getMainPubTopic: () -> String,
    private val getMyPeerId: () -> String,
    /** null = chưa có trạng thái đáng tin (hoặc lỗi DB) → KHÔNG gửi notify lần này. */
    private val getCameraStatuses: () -> List<Pair<String, String>>?
) {
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        job = scope.launch {
            while (isActive) {
                delay(5_000)
                try {
                    val states = sendHeartbeatStates()
                    if (states == null) {
                        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "HEARTBEAT_SKIPPED",
                            "chưa có trạng thái camera đáng tin — không gửi notify lần này")
                    } else {
                        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "HEARTBEAT_SENT",
                            "${states.size} camera(s) " +
                                states.joinToString(" ") { "ch${it.first}=${it.second}" })
                    }
                } catch (e: Exception) {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "HEARTBEAT_ERROR", e.message ?: "")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Trigger an immediate notify_channels_state outside the periodic heartbeat loop. */
    fun triggerNow() {
        scope.launch {
            try {
                val states = sendHeartbeatStates()
                if (states == null) {
                    AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "NOTIFY_TRIGGER_SKIP",
                        "chưa có trạng thái camera đáng tin — nhịp heartbeat sẽ gửi sau")
                } else {
                    AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "NOTIFY_TRIGGERED",
                        "${states.size} camera(s) notified immediately")
                }
            } catch (e: Exception) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "NOTIFY_TRIGGER_ERROR", e.message ?: "")
            }
        }
    }

    /**
     * Publishes the notify and returns the (channelID, status) pairs actually sent, hoặc null khi
     * không gửi (chưa kết nối, hoặc chưa có trạng thái camera đáng tin).
     */
    private fun sendHeartbeatStates(): List<Pair<String, String>>? {
        val topic = getMainPubTopic()
        val peerId = getMyPeerId()
        if (topic.isBlank() || peerId.isBlank()) return null

        val cameraStatuses = getCameraStatuses() ?: return null
        val channels = buildJsonArray {
            cameraStatuses.forEach { (channelID, status) ->
                addJsonObject {
                    put("channelID", channelID)
                    put("status", status)
                }
            }
        }

        val payload = buildJsonObject {
            put("context", "signal.message")
            put("type", "event.data")
            put("source_id", peerId)
            put("target_id", "server")
            put("auth", buildJsonObject { put("type", "inherit") })
            put("version", "1.0.2.3")
            put("data_encode_type", "none")
            put("data", buildJsonObject {
                put("event_id", "")
                put("time", System.currentTimeMillis())
                put("data", buildJsonObject {
                    put("envelope", buildJsonObject {
                        put("header", buildJsonObject { put("component", "notify_service") })
                        put("body", buildJsonObject {
                            put("func", "notify_channels_state")
                            put("status", true)
                            put("data", buildJsonObject {
                                put("status", true)
                                put("data", buildJsonObject {
                                    put("channels", channels)
                                })
                            })
                        })
                    })
                })
            })
        }

        publish(topic, payload.toString())
        return cameraStatuses
    }
}
