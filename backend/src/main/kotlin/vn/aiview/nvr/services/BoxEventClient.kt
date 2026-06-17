package vn.aiview.nvr.services

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.AIEvents
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.plugins.mqttEventFlow
import vn.aiview.nvr.utils.AppLogger

/**
 * Connects to the AiView Box WebSocket event stream (ws://{ip}:{port}/ws/events) as a client,
 * normalises Box AI events, and feeds them into the same mqttEventFlow that Frigate MQTT events
 * use — so the frontend sees Box detections exactly like Frigate detections.
 *
 * Validation rules:
 *  - Events whose cam_id does not match any NVR camera channel_id are silently dropped.
 *  - Malformed JSON / missing required fields are logged at WARN and dropped.
 *  - Duplicate ids (Box re-pushes 10 history events on reconnect) trigger a PERSIST_SKIP log, not an error.
 *
 * Lifecycle: call start(ip, port) when integration settings change; call stop() to disconnect.
 */
object BoxEventClient {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private val httpClient = HttpClient(CIO) {
        install(WebSockets)
    }

    fun start(ip: String, port: Int) {
        if (ip.isBlank()) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "BOX_WS", "START_SKIP", "box_ip is blank — not connecting")
            return
        }
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                try {
                    connect(ip, port)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "BOX_WS", "DISCONNECTED", "${e.javaClass.simpleName}: ${e.message} — retry in 10s")
                }
                delay(10_000)
            }
        }
        AppLogger.info(AppLogger.Module.SYSTEM, "BOX_WS", "CLIENT_STARTED", "target=ws://$ip:$port/ws/events")
    }

    fun stop() {
        if (job == null) return
        job?.cancel()
        job = null
        AppLogger.info(AppLogger.Module.SYSTEM, "BOX_WS", "CLIENT_STOPPED", "")
    }

    private suspend fun connect(ip: String, port: Int) {
        httpClient.webSocket(host = ip, port = port, path = "/ws/events") {
            AppLogger.info(AppLogger.Module.SYSTEM, "BOX_WS", "CONNECTED", "ws://$ip:$port/ws/events")

            val pingJob = launch {
                while (isActive) {
                    delay(20_000)
                    runCatching { send(Frame.Text("ping")) }
                        .onFailure { AppLogger.warn(AppLogger.Module.SYSTEM, "BOX_WS", "PING_FAIL", it.message ?: "") }
                }
            }

            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            if (text == "pong") {
                                AppLogger.info(AppLogger.Module.SYSTEM, "BOX_WS", "PONG_RECEIVED", "")
                                continue
                            }
                            handleBoxEvent(text)
                        }
                        is Frame.Ping -> {
                            send(Frame.Pong(frame.data))
                        }
                        is Frame.Close -> {
                            AppLogger.info(AppLogger.Module.SYSTEM, "BOX_WS", "CLOSE_FRAME", frame.readReason()?.message ?: "")
                        }
                        else -> { /* other frames ignored */ }
                    }
                }
            } finally {
                pingJob.cancel()
                AppLogger.info(AppLogger.Module.SYSTEM, "BOX_WS", "CLOSED", "ws://$ip:$port/ws/events")
            }
        }
    }

    private suspend fun handleBoxEvent(rawJson: String) {
        // --- Parse outer envelope ---
        val json = runCatching { Json.parseToJsonElement(rawJson).jsonObject }.getOrElse {
            AppLogger.warn(AppLogger.Module.SYSTEM, "BOX_WS", "PARSE_ERROR", "not valid JSON: ${rawJson.take(120)}")
            return
        }

        val type = json["type"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (type == "error") {
            AppLogger.warn(AppLogger.Module.SYSTEM, "BOX_WS", "BOX_ERROR", json["message"]?.jsonPrimitive?.content ?: rawJson.take(120))
            return
        }

        val id = json["id"]?.jsonPrimitive?.content
        if (id.isNullOrBlank()) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "BOX_WS", "MISSING_ID", "drop: ${rawJson.take(120)}")
            return
        }

        if (type == null) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "BOX_WS", "MISSING_TYPE", "drop id=$id")
            return
        }

        val timestampMs = json["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()

        // --- Parse payload (JSON-string inside the envelope) ---
        val payloadStr = json["payload"]?.jsonPrimitive?.content ?: "{}"
        val payload = runCatching { Json.parseToJsonElement(payloadStr).jsonObject }.getOrElse {
            AppLogger.warn(AppLogger.Module.SYSTEM, "BOX_WS", "PAYLOAD_PARSE_ERROR", "id=$id payload=${payloadStr.take(120)}")
            JsonObject(emptyMap())
        }

        val camId = payload["cam_id"]?.jsonPrimitive?.content ?: ""
        val objects = payload["objects"]?.jsonArray

        // --- Camera validation: cam_id must map to a known NVR camera ---
        if (camId.isBlank()) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "BOX_WS", "MISSING_CAM_ID", "drop id=$id type=$type")
            return
        }
        val cameraSlug = resolveCameraSlug(camId)
        if (cameraSlug == null) {
            // Box has a camera that NVR doesn't know about — silent drop as requested
            AppLogger.info(AppLogger.Module.SYSTEM, "BOX_WS", "CAM_NOT_FOUND", "drop id=$id cam_id=$camId (not in NVR)")
            return
        }

        val label = buildLabel(type, objects)
        val eventId = "box-$id"
        val objectCount = objects?.size ?: 0

        AppLogger.info(
            AppLogger.Module.SYSTEM, "BOX_WS", "EVENT_RECEIVED",
            "id=$eventId cam_id=$camId→slug=$cameraSlug type=$type objects=$objectCount label=$label ts=$timestampMs"
        )

        // --- Persist first; emit only for genuinely new events (Box re-sends same id with increasing object counts) ---
        val isNew = persistBoxEvent(eventId, cameraSlug, label, timestampMs)
        if (!isNew) return

        val detEvent = buildJsonObject {
            put("type", "detection")
            put("camera", cameraSlug)
            put("id", eventId)
            put("label", label)
            put("score", 1.0)
            // Box payload has pixel bbox but frame resolution unknown → zero-box (badge still shows, no drawn rect)
            put("box", JsonArray(List(4) { JsonPrimitive(0.0) }))
            put("timestamp", System.currentTimeMillis())
            put("source", "box")
        }
        mqttEventFlow.emit(detEvent.toString())
        AppLogger.info(AppLogger.Module.SYSTEM, "BOX_WS", "EVENT_EMITTED", "id=$eventId camera=$cameraSlug label=$label")
    }

    /**
     * Map Box cam_id (= NVR channel_id set when NVR provisions the camera to Box) to the NVR slug.
     * Returns null if the camera doesn't exist in this NVR → caller should drop the event silently.
     */
    private fun resolveCameraSlug(camId: String): String? = runCatching {
        transaction {
            Cameras.selectAll().where { Cameras.channelId eq camId }.firstOrNull()?.get(Cameras.slug)
        }
    }.getOrElse { e ->
        AppLogger.warn(AppLogger.Module.SYSTEM, "BOX_WS", "RESOLVE_CAM_ERROR", "cam_id=$camId err=${e.message}")
        null
    }

    /**
     * Build a human-readable label from Box event type + first object's name.
     * e.g. type="facerecognition", name="Nguyen Van A" → "facerecognition: Nguyen Van A"
     */
    private fun buildLabel(type: String, objects: JsonArray?): String {
        val firstName = objects
            ?.firstOrNull()
            ?.jsonObject
            ?.get("name")
            ?.jsonPrimitive
            ?.content
            ?.takeIf { it.isNotBlank() }
        return if (firstName != null) "$type: $firstName" else type
    }

    /** Returns true if the event was new and persisted, false if duplicate (PK conflict). */
    private fun persistBoxEvent(id: String, cameraSlug: String, label: String, timestampMs: Long): Boolean =
        runCatching {
            transaction {
                AIEvents.insert {
                    it[AIEvents.id] = id
                    it[AIEvents.cameraSlug] = cameraSlug
                    it[AIEvents.label] = label
                    it[AIEvents.score] = null
                    it[AIEvents.startTime] = timestampMs / 1000
                    it[AIEvents.endTime] = timestampMs / 1000
                    it[AIEvents.snapshotPath] = null
                    it[AIEvents.eventSource] = "box"
                }
            }
            AppLogger.info(AppLogger.Module.SYSTEM, "BOX_WS", "EVENT_PERSISTED", "id=$id camera=$cameraSlug")
            true
        }.getOrElse { e ->
            AppLogger.info(AppLogger.Module.SYSTEM, "BOX_WS", "PERSIST_SKIP", "id=$id (${e.message?.take(60)})")
            false
        }
}
