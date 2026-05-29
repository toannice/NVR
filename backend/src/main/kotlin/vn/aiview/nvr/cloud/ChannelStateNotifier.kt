package vn.aiview.nvr.cloud

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import vn.aiview.nvr.utils.AppLogger

class ChannelStateNotifier(
    private val publish: (topic: String, payload: String) -> Unit,
    private val getMainPubTopic: () -> String,
    private val getMyPeerId: () -> String,
    private val getCameraStatuses: () -> List<Pair<String, String>>
) {
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        job = scope.launch {
            while (isActive) {
                delay(5_000)
                try {
                    val count = sendHeartbeat()
                    AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "HEARTBEAT_SENT", "$count camera(s)")
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

    private fun sendHeartbeat(): Int {
        val topic = getMainPubTopic()
        val peerId = getMyPeerId()
        if (topic.isBlank() || peerId.isBlank()) return 0

        val cameraStatuses = getCameraStatuses()
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
        return cameraStatuses.size
    }
}
