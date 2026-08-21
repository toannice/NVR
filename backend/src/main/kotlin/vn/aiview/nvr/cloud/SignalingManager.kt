package vn.aiview.nvr.cloud

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.cloud.handlers.PlaybackCommandHandler
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.services.PlaybackService
import vn.aiview.nvr.services.StreamOnlineTracker
import vn.aiview.nvr.utils.AppLogger
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

enum class CloudConnectionState { DISABLED, CONNECTING, CONNECTED, ERROR }

object SignalingManager {
    var connectionState: CloudConnectionState = CloudConnectionState.DISABLED
        private set
    var lastError: String? = null
        private set

    var myPeerId: String = ""
        private set
    var mainPubTopic: String = ""
        private set
    var deviceSerial: String = ""

    val sessionPubBySubTopic = ConcurrentHashMap<String, String>()
    val liveSessionStreamIds = ConcurrentHashMap<String, String>()

    private var mqttClient: MqttAsyncClient? = null
    private var iceServers: List<IceServer> = emptyList()
    private var notifier: ChannelStateNotifier? = null
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = HttpClient(CIO) { engine { requestTimeout = 10_000 } }

    fun setDisabled() { connectionState = CloudConnectionState.DISABLED; lastError = null }
    fun setConnecting() { connectionState = CloudConnectionState.CONNECTING; lastError = null }
    fun setError(msg: String) { connectionState = CloudConnectionState.ERROR; lastError = msg }

    fun connect(mqttInfo: MqttConnectionInfo, servers: List<IceServer>) {
        iceServers = servers
        myPeerId = mqttInfo.peerId
        mainPubTopic = mqttInfo.pubTopic

        val client = MqttAsyncClient(mqttInfo.serverUri, mqttInfo.clientId, MemoryPersistence())
        mqttClient = client

        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            maxReconnectDelay = 300_000
            isCleanSession = true
            connectionTimeout = 15
            // Thiết bị mất điện/mất mạng thì KHÔNG ai gửi được "device off" — cloud phải tự suy ra
            // từ lúc broker thấy client chết. Paho mặc định 60s (broker chờ 1.5× = 90s). Hạ xuống
            // 30s để cloud báo thiết bị offline trong ~45s thay vì ~90s. Chi phí: 1 PINGREQ/30s.
            keepAliveInterval = 30
            userName = mqttInfo.mqttUsername
            password = mqttInfo.mqttPassword.toCharArray()
        }

        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "MQTT_CONNECTED", "Connected to $serverURI reconnect=$reconnect")
                client.subscribe(mqttInfo.subTopic, 1)
                connectionState = CloudConnectionState.CONNECTED
                lastError = null
                startNotifier(client)
                if (reconnect) {
                    scheduleReHandshake()
                }
            }

            override fun connectionLost(cause: Throwable?) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "MQTT_LOST", cause?.message ?: "")
                connectionState = CloudConnectionState.CONNECTING
                notifier?.stop()
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                if (topic != null && message != null) {
                    scope.launch { handleMqttMessage(topic, String(message.payload)) }
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        try {
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {}
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    val msg = exception?.message ?: "Unknown error"
                    AppLogger.error(AppLogger.Module.SYSTEM, "CLOUD", "MQTT_CONNECT_ERROR", msg)
                    setError(msg)
                }
            })
        } catch (e: Exception) {
            AppLogger.error(AppLogger.Module.SYSTEM, "CLOUD", "MQTT_CONNECT_ERROR", e.message ?: "", e)
            setError(e.message ?: "MQTT connect failed")
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        notifier?.stop()
        sessionPubBySubTopic.clear()
        liveSessionStreamIds.clear()
        try { mqttClient?.disconnect() } catch (_: Exception) {}
        try { mqttClient?.close() } catch (_: Exception) {}
        mqttClient = null
        connectionState = CloudConnectionState.DISABLED
    }

    fun publish(topic: String, payload: String) {
        try {
            val client = mqttClient ?: return
            if (client.isConnected) {
                val msg = MqttMessage(payload.toByteArray()).apply { qos = 1 }
                client.publish(topic, msg)
            }
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "PUBLISH_FAIL", "$topic: ${e.message}")
        }
    }

    /**
     * Trigger an immediate notify_channels_state to cloud (e.g. after a camera is added/deleted
     * from the local Web UI so the cloud sees the change without waiting for the 5-second heartbeat).
     */
    fun triggerChannelStateNotification() {
        val n = notifier
        if (n == null) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "NOTIFY_TRIGGER_SKIP",
                "No active notifier (not connected to cloud)")
            return
        }
        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "NOTIFY_TRIGGER", "Triggering immediate channel state notification")
        n.triggerNow()
    }

    private fun startNotifier(client: MqttAsyncClient) {
        notifier?.stop()
        notifier = ChannelStateNotifier(
            publish = { topic, payload -> publish(topic, payload) },
            getMainPubTopic = { mainPubTopic },
            getMyPeerId = { myPeerId },
            getCameraStatuses = { getCameraStatusList() }
        ).also { it.start() }
    }

    /**
     * Per-channel state for notify_channels_state. Must use the same values and the same source of
     * truth as `get_channels` ("online"/"offline" from [StreamOnlineTracker]) — the cloud renders
     * its device list from this push and its camera list from get_channels, so any divergence shows
     * up as a camera that is online in one screen and offline in the other. "connected" (the old
     * hardcoded value) is not a value the cloud understands → it fell back to OFFLINE forever.
     */
    private fun getCameraStatusList(): List<Pair<String, String>>? {
        return try {
            val channels = transaction {
                Cameras.selectAll().mapNotNull { row -> row[Cameras.channelId] }
            }
            // Không có camera nào là một sự thật hợp lệ — vẫn gửi để cloud xoá hết khỏi danh sách.
            if (channels.isEmpty()) return emptyList()
            // Kênh chưa có trạng thái chắc chắn thì BỎ QUA, không đoán: cloud giữ giá trị cũ của
            // nó, còn đoán sai một nhịp là một thông báo giả (xem StreamOnlineTracker.stateOf).
            val known = channels.mapNotNull { ch ->
                StreamOnlineTracker.stateOf(ch)?.let { ch to if (it) "online" else "offline" }
            }
            if (known.isEmpty()) null else known
        } catch (e: Exception) {
            // Lỗi DB: gửi danh sách rỗng ở đây sẽ bị cloud hiểu là "thiết bị không còn camera nào".
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "CAMERA_STATUS_FAIL", e.message ?: "")
            null
        }
    }

    private suspend fun handleMqttMessage(topic: String, payload: String) {
        try {
            val json = Json.parseToJsonElement(payload).jsonObject
            val context = json["context"]?.jsonPrimitive?.content ?: return
            val type = json["type"]?.jsonPrimitive?.content ?: return
            val encodeType = json["data_encode_type"]?.jsonPrimitive?.content ?: "none"

            AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "MSG_IN",
                "context=$context type=$type encode=$encodeType topic=${topic.takeLast(30)}")

            val dataRaw = json["data"]
            val dataObj: JsonObject? = if (encodeType == "base64") {
                try {
                    val decoded = String(Base64.getDecoder().decode(dataRaw?.jsonPrimitive?.content ?: ""))
                    Json.parseToJsonElement(decoded).jsonObject
                } catch (e: Exception) {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "BASE64_DECODE_FAIL", e.message ?: "")
                    null
                }
            } else {
                dataRaw as? JsonObject
            }

            when {
                context == "signal.connect.channel" && type == "new.response" -> {
                    AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "STEP2_SESSION", "New session from server — subscribing topics")
                    handleConnectChannel(json, dataObj)
                }

                context == "signal.manager" && type == "send-action" -> {
                    AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "STEP3_CMD", "Dispatching manager command")
                    CommandDispatcher.dispatch(json, dataObj)
                }

                context == "signal.webrtc" && type == "offer" -> {
                    val extendType = json["extend"]?.jsonObject?.get("type")?.jsonPrimitive?.content ?: ""
                    AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "STEP3_WEBRTC", "WebRTC offer type=$extendType offerId=${json["offerid"]?.jsonPrimitive?.content}")
                    when {
                        extendType == "LIVE" || extendType == "LIVE_DEBUG" ->
                            handleLiveOffer(topic, json, dataObj)
                        extendType == "PLAYBACK" || extendType == "PLAYBACK_DEBUG" ->
                            handlePlaybackOffer(topic, json, dataObj)
                        extendType == "DATA" ->
                            handleDataOffer(topic, json, dataObj)
                        else ->
                            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "WEBRTC_UNKNOWN_TYPE", "extend.type=$extendType")
                    }
                }

                context == "signal.webrtc" && type == "icecandidate" -> {
                    val offerId = json["offerid"]?.jsonPrimitive?.content ?: ""
                    if (liveSessionStreamIds.containsKey(offerId)) {
                        // Drop silently — go2rtc handles ICE internally
                    } else {
                        DataChannelManagerDesktop.handleIceCandidate(json.toString())
                    }
                }

                else -> {
                    AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "MSG_UNHANDLED", "context=$context type=$type")
                }
            }
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "MSG_HANDLE_ERROR", "${e::class.simpleName}: ${e.message}")
        }
    }

    private fun handleConnectChannel(json: JsonObject, data: JsonObject?) {
        val channelData = data ?: return
        val transports = channelData["transports"]?.jsonObject
        val transportInfos = transports?.get("transport_infos")?.jsonArray ?: return

        var sessionSubTopic: String? = null
        var sessionPubTopic: String? = null

        transportInfos.forEach { info ->
            val infoObj = info.jsonObject
            if (infoObj["type"]?.jsonPrimitive?.content == "mqtt") {
                val mqttData = infoObj["data"]?.jsonObject
                val functions = mqttData?.get("functions")?.jsonArray ?: return@forEach
                functions.forEach { fn ->
                    val fnObj = fn.jsonObject
                    val func = fnObj["function"]?.jsonPrimitive?.content
                    val path = fnObj["path"]?.jsonPrimitive?.content ?: return@forEach
                    when (func) {
                        "received" -> sessionSubTopic = path
                        "send" -> sessionPubTopic = path
                    }
                }
            }
        }

        val sub = sessionSubTopic ?: run {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "SESSION_MISSING_SUB", "No sessionSubTopic in response")
            return
        }
        val pub = sessionPubTopic ?: run {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "SESSION_MISSING_PUB", "No sessionPubTopic in response")
            return
        }

        val channelId = channelData["channel_id"]?.jsonPrimitive?.content ?: ""
        sessionPubBySubTopic[sub] = pub
        mqttClient?.subscribe(sub, 1)
        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "STEP2_SESSION_OK",
            "channelId=$channelId sub=...${sub.takeLast(20)} pub=...${pub.takeLast(20)}")

        publishSessionEvent("peer.event.join_channel", pub, channelId)
        publishSessionEvent("peer.event.ready", pub, channelId)
    }

    private fun publishSessionEvent(eventType: String, pubTopic: String, channelId: String) {
        val payload = buildJsonObject {
            put("context", "signal.connect.channel")
            put("type", eventType)
            put("source_id", myPeerId)
            put("target_id", "server")
            put("channel_id", channelId)
            put("auth", buildJsonObject { put("type", "inherit") })
            put("version", "1.0.2.3")
            put("data_encode_type", "none")
            put("data", buildJsonObject {})
        }
        publish(pubTopic, payload.toString())
    }

    private suspend fun handleLiveOffer(topic: String, json: JsonObject, data: JsonObject?) {
        val offerId = json["offerid"]?.jsonPrimitive?.content ?: return
        val profileToken = json["extend"]?.jsonObject?.get("src")?.jsonPrimitive?.content ?: return
        val sessionPubTopic = sessionPubBySubTopic[topic] ?: return

        val sdpText = when {
            json["data_encode_type"]?.jsonPrimitive?.content == "base64" -> {
                val decoded = String(Base64.getDecoder().decode(json["data"]?.jsonPrimitive?.content ?: ""))
                Json.parseToJsonElement(decoded).jsonObject["sdp"]?.jsonPrimitive?.content
            }
            else -> data?.get("sdp")?.jsonPrimitive?.content
        } ?: return

        val streamId = resolveStreamId(profileToken) ?: run {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "LIVE_TOKEN_UNRESOLVED",
                "offerId=$offerId token=$profileToken")
            publishLiveErrorAnswer(json, sessionPubTopic, offerId, profileToken)
            return
        }

        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "STEP3_LIVE_OFFER",
            "offerId=$offerId token=$profileToken → stream=$streamId")
        liveSessionStreamIds[offerId] = streamId

        try {
            val answerSdp = negotiateLiveSdp(streamId, sdpText, offerId) ?: run {
                publishLiveErrorAnswer(json, sessionPubTopic, offerId, profileToken)
                return
            }

            val answerJson = buildJsonObject {
                put("type", "answer")
                put("sdp", answerSdp)
            }.toString()
            val answerBase64 = Base64.getEncoder().encodeToString(answerJson.toByteArray())

            val answerPayload = buildJsonObject {
                put("context", "signal.webrtc")
                put("type", "answer")
                put("source_id", myPeerId)
                put("target_id", json["source_id"]?.jsonPrimitive?.content ?: "server")
                put("offerid", offerId)
                put("channel_id", json["channel_id"]?.jsonPrimitive?.content ?: "")
                put("auth", buildJsonObject { put("type", "inherit") })
                put("version", "1.0.2.3")
                put("data_encode_type", "base64")
                put("data", answerBase64)
            }
            publish(sessionPubTopic, answerPayload.toString())
        } catch (e: Exception) {
            AppLogger.error(AppLogger.Module.SYSTEM, "CLOUD", "LIVE_OFFER_ERROR", e.message ?: "", e)
        }
    }

    // ── H.265 (HEVC) live-view fallback ──────────────────────────────────────────────
    // WebRTC has no H.265 profile, so go2rtc answers 500 "codecs not matched: video:H265 =>
    // video:VP8, VP9, H264, AV1" for any camera whose main stream is HEVC. Symptom: cloud live
    // view is black forever while the LOCAL web UI works fine (it uses MSE/mp4, which does play
    // HEVC). Before this fallback the device just logged the 500 and went silent, so the cloud
    // retried the same doomed offer in a loop with nothing pointing at the cause.
    //
    // Recovery order, cheapest first:
    //   1. the camera's own sub stream — almost always H.264, costs nothing
    //   2. transcode HEVC→H.264 via go2rtc ffmpeg (VAAPI when /dev/dri exists, else CPU)
    // H.264 cameras never touch this path: attempt 1 succeeds and returns immediately.
    private val hasVaapi: Boolean by lazy { java.io.File("/dev/dri/renderD128").exists() }

    /** POST an SDP offer to go2rtc. Returns the answer SDP, or null with the error body logged. */
    private suspend fun postOffer(streamId: String, sdpText: String, offerId: String): Pair<String?, String> {
        val response = http.post("http://127.0.0.1:1984/api/webrtc?src=$streamId") {
            contentType(ContentType("application", "sdp"))
            setBody(sdpText)
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) return body to ""
        AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "LIVE_GO2RTC_ERROR",
            "go2rtc ${response.status.value} for stream=$streamId offerId=$offerId body=${body.take(200)}")
        return null to body
    }

    /**
     * Negotiate a live SDP answer, falling back off H.265 when the browser and the camera share
     * no codec. Returns null only when every route failed (caller publishes the error answer).
     */
    private suspend fun negotiateLiveSdp(streamId: String, sdpText: String, offerId: String): String? {
        val (answer, errBody) = postOffer(streamId, sdpText, offerId)
        if (answer != null) return answer

        // Only the codec mismatch is recoverable here. A missing stream or a dead camera must keep
        // failing loudly rather than being masked by a transcode attempt that will also fail.
        if (!errBody.contains("codecs not matched", ignoreCase = true)) return null

        AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "LIVE_CODEC_MISMATCH",
            "stream=$streamId offerId=$offerId — camera is likely H.265; trying fallbacks")

        // 1. Sub stream (H.264 on every camera we've shipped). Only meaningful for a main stream.
        if (streamId.endsWith("_main")) {
            val subId = streamId.removeSuffix("_main") + "_sub"
            val (subAnswer, _) = postOffer(subId, sdpText, offerId)
            if (subAnswer != null) {
                AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "LIVE_FALLBACK_SUB",
                    "offerId=$offerId $streamId → $subId (H.265 main, serving sub instead)")
                liveSessionStreamIds[offerId] = subId
                return subAnswer
            }
        }

        // 2. Transcode HEVC→H.264. Registered under a distinct name so the untouched original
        //    stream keeps feeding recording (stream copy, still full-quality H.265).
        val h264Id = "${streamId}_h264"
        val src = if (hasVaapi) "ffmpeg:$streamId#video=h264#hardware" else "ffmpeg:$streamId#video=h264"
        if (!Go2RtcClient.registerStreamSync(h264Id, src)) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "LIVE_TRANSCODE_REGISTER_FAIL",
                "offerId=$offerId could not register $h264Id")
            return null
        }
        val (h264Answer, _) = postOffer(h264Id, sdpText, offerId)
        if (h264Answer != null) {
            AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "LIVE_FALLBACK_TRANSCODE",
                "offerId=$offerId $streamId → $h264Id (hardware=$hasVaapi)")
            liveSessionStreamIds[offerId] = h264Id
            return h264Answer
        }
        AppLogger.error(AppLogger.Module.SYSTEM, "CLOUD", "LIVE_ALL_FALLBACKS_FAILED",
            "offerId=$offerId stream=$streamId — no H.264 route available", null)
        return null
    }

    /**
     * Handle WebRTC PLAYBACK offer from cloud.
     * Registers a go2rtc ffmpeg source for the requested recording file, then forwards the
     * SDP offer to go2rtc (same pattern as LIVE) and publishes the answer back via MQTT.
     *
     * extend.src = recording token "{channelId}:{startSec}:{endSec}"
     */
    private suspend fun handlePlaybackOffer(topic: String, json: JsonObject, data: JsonObject?) {
        val offerId = json["offerid"]?.jsonPrimitive?.content ?: return
        val token = json["extend"]?.jsonObject?.get("src")?.jsonPrimitive?.content ?: run {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "PLAYBACK_NO_TOKEN",
                "offerId=$offerId missing extend.src")
            return
        }
        val sessionPubTopic = sessionPubBySubTopic[topic] ?: run {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "PLAYBACK_NO_SESSION",
                "offerId=$offerId topic not in sessions")
            return
        }
        val sdpText = when {
            json["data_encode_type"]?.jsonPrimitive?.content == "base64" -> {
                val decoded = String(Base64.getDecoder().decode(json["data"]?.jsonPrimitive?.content ?: ""))
                Json.parseToJsonElement(decoded).jsonObject["sdp"]?.jsonPrimitive?.content
            }
            else -> data?.get("sdp")?.jsonPrimitive?.content
        } ?: run {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "PLAYBACK_NO_SDP",
                "offerId=$offerId no SDP in offer")
            return
        }

        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "STEP3_PLAYBACK_OFFER",
            "offerId=$offerId token=$token")

        val parsed = PlaybackService.parseToken(token) ?: run {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "PLAYBACK_TOKEN_INVALID",
                "offerId=$offerId token=$token")
            publishPlaybackErrorAnswer(json, sessionPubTopic, offerId, token)
            return
        }

        val slug = PlaybackService.channelToSlug(parsed.channelId) ?: run {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "PLAYBACK_NO_SLUG",
                "offerId=$offerId channelId=${parsed.channelId}")
            publishPlaybackErrorAnswer(json, sessionPubTopic, offerId, token)
            return
        }

        // Frigate HLS VOD endpoint — all segments in the session play continuously.
        // dc-bridge's ffmpeg fetches the playlist from 127.0.0.1:5000 directly.
        val vodUrl = PlaybackService.vodUrl(slug, parsed.startSec, parsed.endSec)

        // dc-bridge (Pion) owns the playback peer so the cloud's control DataChannel
        // (post_select_record / post_control_record) reaches the backend — go2rtc would
        // swallow it. Bar switch / seek later swap the ffmpeg source on this same peer.
        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "PLAYBACK_STREAM_READY",
            "offerId=$offerId vodUrl=$vodUrl — forwarding SDP to dc-bridge")

        try {
            val answerSdp = PlaybackPeerManager.handleOffer(sdpText, vodUrl, iceServers)
            if (answerSdp == null) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "PLAYBACK_DCBRIDGE_ERROR",
                    "dc-bridge returned no answer for offerId=$offerId vodUrl=$vodUrl")
                publishPlaybackErrorAnswer(json, sessionPubTopic, offerId, token)
                return
            }
            PlaybackCommandHandler.onPlaybackStarted(parsed.channelId, token)

            val answerJson = buildJsonObject {
                put("type", "answer")
                put("sdp", answerSdp)
            }.toString()
            val answerBase64 = Base64.getEncoder().encodeToString(answerJson.toByteArray())

            val answerPayload = buildJsonObject {
                put("context", "signal.webrtc")
                put("type", "answer")
                put("source_id", myPeerId)
                put("target_id", json["source_id"]?.jsonPrimitive?.content ?: "server")
                put("offerid", offerId)
                put("channel_id", json["channel_id"]?.jsonPrimitive?.content ?: "")
                put("auth", buildJsonObject { put("type", "inherit") })
                put("version", "1.0.2.3")
                put("data_encode_type", "base64")
                put("data", answerBase64)
                put("extend", buildJsonObject {
                    put("type", "PLAYBACK")
                    put("src", token)
                })
            }
            publish(sessionPubTopic, answerPayload.toString())
            AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "PLAYBACK_ANSWER_SENT",
                "offerId=$offerId vodUrl=$vodUrl")
        } catch (e: Exception) {
            AppLogger.error(AppLogger.Module.SYSTEM, "CLOUD", "PLAYBACK_OFFER_ERROR", e.message ?: "", e)
            publishPlaybackErrorAnswer(json, sessionPubTopic, offerId, token)
        }
    }

    private suspend fun publishPlaybackErrorAnswer(
        json: JsonObject,
        sessionPubTopic: String,
        offerId: String,
        token: String,
    ) {
        val errorAnswer = buildJsonObject {
            put("type", "answer")
            put("sdp", "")
            put("error_code", "E0013")
            put("message", "Recording not found")
        }.toString()
        val payload = buildJsonObject {
            put("context", "signal.webrtc")
            put("type", "answer")
            put("source_id", myPeerId)
            put("target_id", json["source_id"]?.jsonPrimitive?.content ?: "server")
            put("offerid", offerId)
            put("channel_id", json["channel_id"]?.jsonPrimitive?.content ?: "")
            put("auth", buildJsonObject { put("type", "inherit") })
            put("version", "1.0.2.3")
            put("data_encode_type", "base64")
            put("data", Base64.getEncoder().encodeToString(errorAnswer.toByteArray()))
            put("extend", buildJsonObject {
                put("type", "PLAYBACK")
                put("src", token)
            })
        }
        publish(sessionPubTopic, payload.toString())
    }

    private fun resolveStreamId(profileToken: String): String? {
        // Constructed token: "MediaProfile{NNN}01" (main) or "MediaProfile{NNN}02" (sub)
        val constructedMatch = Regex("^MediaProfile(\\d{3})(\\d{2})$").find(profileToken)
        if (constructedMatch != null) {
            val channelId = constructedMatch.groupValues[1].trimStart('0').ifEmpty { "0" }
            val streamType = if (constructedMatch.groupValues[2] == "01") "main" else "sub"
            return "${channelId}_${streamType}"
        }
        // Playback stream registered by post_select_record — use directly as go2rtc stream name
        if (profileToken.startsWith("playback_")) {
            AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "RESOLVE_PLAYBACK_STREAM",
                "token=$profileToken → using as go2rtc playback stream directly")
            return profileToken
        }
        // Real ONVIF token stored in the unified Cameras table (003-cloud-data-sync)
        return try {
            transaction {
                Cameras.selectAll().where { Cameras.mainToken eq profileToken }
                    .firstOrNull()?.get(Cameras.channelId)?.let { "${it}_main" }
                    ?: Cameras.selectAll().where { Cameras.subToken eq profileToken }
                        .firstOrNull()?.get(Cameras.channelId)?.let { "${it}_sub" }
            }
        } catch (_: Exception) { null }
    }

    private suspend fun publishLiveErrorAnswer(
        json: JsonObject,
        sessionPubTopic: String,
        offerId: String,
        token: String,
    ) {
        val errorAnswer = buildJsonObject {
            put("type", "answer")
            put("sdp", "")
            put("error_code", "E0007")
            put("message", "Camera not found")
        }.toString()
        val payload = buildJsonObject {
            put("context", "signal.webrtc")
            put("type", "answer")
            put("source_id", myPeerId)
            put("target_id", json["source_id"]?.jsonPrimitive?.content ?: "server")
            put("offerid", offerId)
            put("channel_id", json["channel_id"]?.jsonPrimitive?.content ?: "")
            put("auth", buildJsonObject { put("type", "inherit") })
            put("version", "1.0.2.3")
            put("data_encode_type", "base64")
            put("data", Base64.getEncoder().encodeToString(errorAnswer.toByteArray()))
            put("extend", buildJsonObject {
                put("type", "LIVE")
                put("src", token)
            })
        }
        publish(sessionPubTopic, payload.toString())
    }

    private suspend fun handleDataOffer(topic: String, json: JsonObject, data: JsonObject?) {
        val offerId = json["offerid"]?.jsonPrimitive?.content ?: return
        val msgId = json["message_id"]?.jsonPrimitive?.content ?: ""
        val channelId = json["channel_id"]?.jsonPrimitive?.content ?: ""
        val targetId = json["source_id"]?.jsonPrimitive?.content ?: "server"
        val sessionPubTopic = sessionPubBySubTopic[topic] ?: run {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "DATA_OFFER_NO_SESSION",
                "topic ...${topic.takeLast(20)} not in sessionPubBySubTopic (sessions: ${sessionPubBySubTopic.size})")
            return
        }

        val sdpText = when {
            json["data_encode_type"]?.jsonPrimitive?.content == "base64" -> {
                val decoded = String(Base64.getDecoder().decode(json["data"]?.jsonPrimitive?.content ?: ""))
                Json.parseToJsonElement(decoded).jsonObject["sdp"]?.jsonPrimitive?.content
            }
            else -> data?.get("sdp")?.jsonPrimitive?.content
        } ?: return

        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "STEP3_DATA_OFFER",
            "offerId=$offerId channelId=$channelId → forwarding to dc-bridge")

        try {
            val answerSdp = DataChannelManagerDesktop.handleOffer(sdpText, offerId, msgId, channelId, targetId, iceServers)
            if (answerSdp == null) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "DC_OFFER_FAIL", "dc-bridge returned no answer — ensure dc-bridge binary is at ./data/dc-bridge")
                return
            }

            val answerJson = buildJsonObject {
                put("type", "answer")
                put("sdp", answerSdp)
            }.toString()
            val answerBase64 = Base64.getEncoder().encodeToString(answerJson.toByteArray())

            val answerPayload = buildJsonObject {
                put("context", "signal.webrtc")
                put("type", "answer")
                put("source_id", myPeerId)
                put("target_id", targetId)
                put("offerid", offerId)
                put("channel_id", channelId)
                put("auth", buildJsonObject { put("type", "inherit") })
                put("version", "1.0.2.3")
                put("data_encode_type", "base64")
                put("data", answerBase64)
            }
            publish(sessionPubTopic, answerPayload.toString())
        } catch (e: Exception) {
            AppLogger.error(AppLogger.Module.SYSTEM, "CLOUD", "DATA_OFFER_ERROR", e.message ?: "", e)
        }
    }

    private fun scheduleReHandshake() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var delayMs = 5_000L
            while (isActive) {
                delay(delayMs)
                try {
                    CloudReconnectContext.reconnect?.invoke()
                    break
                } catch (e: Exception) {
                    delayMs = minOf(delayMs * 2, 300_000L)
                    AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "REHANDSHAKE_RETRY", "Retrying in ${delayMs / 1000}s: ${e.message}")
                }
            }
        }
    }
}
