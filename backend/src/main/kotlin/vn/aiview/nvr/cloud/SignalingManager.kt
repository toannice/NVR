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
import vn.aiview.nvr.models.Cameras
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

    private fun startNotifier(client: MqttAsyncClient) {
        notifier?.stop()
        notifier = ChannelStateNotifier(
            publish = { topic, payload -> publish(topic, payload) },
            getMainPubTopic = { mainPubTopic },
            getMyPeerId = { myPeerId },
            getCameraStatuses = { getCameraStatusList() }
        ).also { it.start() }
    }

    private fun getCameraStatusList(): List<Pair<String, String>> {
        return try {
            transaction {
                Cameras.selectAll().mapNotNull { row ->
                    row[Cameras.channelId]?.let { it to "connected" }
                }
            }
        } catch (_: Exception) { emptyList() }
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
            val response = http.post("http://127.0.0.1:1984/api/webrtc?src=$streamId") {
                contentType(ContentType("application", "sdp"))
                setBody(sdpText)
            }
            if (!response.status.isSuccess()) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "LIVE_GO2RTC_ERROR",
                    "go2rtc ${response.status.value} for stream=$streamId offerId=$offerId")
                publishLiveErrorAnswer(json, sessionPubTopic, offerId, profileToken)
                return
            }
            val answerSdp = response.bodyAsText()

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

    private fun resolveStreamId(profileToken: String): String? {
        // Constructed token: "MediaProfile{NNN}01" (main) or "MediaProfile{NNN}02" (sub)
        val constructedMatch = Regex("^MediaProfile(\\d{3})(\\d{2})$").find(profileToken)
        if (constructedMatch != null) {
            val channelId = constructedMatch.groupValues[1].trimStart('0').ifEmpty { "0" }
            val streamType = if (constructedMatch.groupValues[2] == "01") "main" else "sub"
            return "${channelId}_${streamType}"
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
