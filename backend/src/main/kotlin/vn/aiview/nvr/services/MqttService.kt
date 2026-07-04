package vn.aiview.nvr.services

import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import kotlinx.serialization.json.*
import java.util.UUID
import vn.aiview.nvr.plugins.mqttEventFlow
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.insert
import vn.aiview.nvr.models.AIEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import org.slf4j.LoggerFactory

object MqttService {
    private val logger = LoggerFactory.getLogger(MqttService::class.java)
    private var client: MqttAsyncClient? = null
    // Store active detections to send immediately to new WS connections
    private val activeDetections = mutableMapOf<String, MutableMap<String, JsonObject>>()

    fun start() {
        val brokerUrl = System.getenv("MQTT_URL") ?: "tcp://localhost:1883"
        val clientId = "nvr-wrapper-kt-" + UUID.randomUUID().toString().substring(0, 8)

        try {
            client = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())
            
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
            }

            client?.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    logger.info("MQTT Connected to $serverURI")
                    client?.subscribe("frigate/events", 0)
                }

                override fun connectionLost(cause: Throwable?) {
                    logger.warn("MQTT Connection lost: ${cause?.message}")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (message != null) {
                        try {
                            val payloadString = String(message.payload)
                            val json = Json.parseToJsonElement(payloadString).jsonObject
                            
                            if (topic == "frigate/events") {
                                handleEventSource(parseFrigateEvent(json))
                            } else if (topic?.startsWith("onvif/metadata/") == true) {
                                // Prepare for Profile M metadata topics
                                handleEventSource(parseOnvifProfileMEvent(topic, json))
                            }
                        } catch (e: Exception) {
                            logger.error("Error parsing MQTT message: ${e.message}")
                        }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            client?.connect(options)
        } catch (e: Exception) {
            logger.error("Failed to start MQTT service: ${e.message}")
        }
    }

    data class NormalizedEvent(
        val type: String, // "new", "update", "end"
        val id: String,
        val camera: String,
        val label: String,
        val score: Double,
        val box: List<Double>,
        val startTimeMs: Long,
        val endTimeMs: Long?,
        val hasSnapshot: Boolean,
        val originalJson: JsonObject
    )

    private fun parseFrigateEvent(eventJson: JsonObject): NormalizedEvent? {
        val type = eventJson["type"]?.jsonPrimitive?.content ?: return null
        val after = eventJson["after"]?.jsonObject ?: return null
        
        return NormalizedEvent(
            type = type,
            id = after["id"]?.jsonPrimitive?.content ?: return null,
            camera = after["camera"]?.jsonPrimitive?.content ?: return null,
            label = after["label"]?.jsonPrimitive?.content ?: "unknown",
            score = after["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            box = after["box"]?.jsonArray?.map { it.jsonPrimitive.doubleOrNull ?: 0.0 } ?: listOf(0.0, 0.0, 0.0, 0.0),
            startTimeMs = ((after["start_time"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
            endTimeMs = after["end_time"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() },
            hasSnapshot = after["has_snapshot"]?.jsonPrimitive?.booleanOrNull ?: false,
            originalJson = after
        )
    }

    private fun parseOnvifProfileMEvent(topic: String, eventJson: JsonObject): NormalizedEvent? {
        // TODO: ONVIF Profile M standardizes JSON output for bounding boxes and object classification.
        // It provides a 'navigational' path like object.data.bbox.
        // This is a placeholder for actual Profile M parsing based on the XML/JSON spec.
        return null
    }

    private fun handleEventSource(event: NormalizedEvent?) {
        if (event == null) return

        if (event.type == "new" || event.type == "update") {
            // It's a tracking update
            val bbEvent = buildJsonObject {
                put("type", "detection")
                put("camera", event.camera)
                put("id", event.id)
                put("label", event.label)
                put("score", event.score)
                put("box", JsonArray(event.box.map { JsonPrimitive(it) }))
            }
            
            val camMap = activeDetections.getOrPut(event.camera) { mutableMapOf() }
            camMap[event.id] = bbEvent

            // Push to websockets
            CoroutineScope(Dispatchers.IO).launch {
                mqttEventFlow.emit(bbEvent.toString())
            }
            
        } else if (event.type == "end") {
            // Event ended, remove from active
            activeDetections[event.camera]?.remove(event.id)
            
            val endEvent = buildJsonObject {
                put("type", "event_end")
                put("camera", event.camera)
                put("id", event.id)
            }
            CoroutineScope(Dispatchers.IO).launch {
                mqttEventFlow.emit(endEvent.toString())
            }

            // Persist to DB
            persistEventToDb(event)
        }
    }

    private fun persistEventToDb(event: NormalizedEvent) {
        // 5B mode: keep Frigate detect + motion recording running, but don't store Frigate AI
        // events — the UI should surface Box-pushed events only. Live bounding boxes are untouched.
        if (AppSettingsService.isBoxOnly()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                transaction {
                    AIEvents.insert {
                        it[AIEvents.id] = event.id
                        it[cameraSlug] = event.camera
                        it[AIEvents.label] = event.label
                        it[AIEvents.score] = event.score
                        if (event.box.size == 4) {
                            it[boxX] = event.box[0]
                            it[boxY] = event.box[1]
                            it[boxW] = event.box[2]
                            it[boxH] = event.box[3]
                        }
                        
                        it[startTime] = event.startTimeMs / 1000 // DB expects seconds
                        if (event.endTimeMs != null) {
                            it[endTime] = event.endTimeMs / 1000
                        }
                        
                        if (event.hasSnapshot) {
                            it[snapshotPath] = "/api/frigate/api/events/${event.id}/snapshot.jpg"
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to persist event to DB: ${e.message}")
            }
        }
    }

    fun getActiveDetectionsCurrentStateJson(): String {
        val events = activeDetections.values.flatMap { it.values }
        return JsonArray(events).toString()
    }

    fun publish(topic: String, payload: String, retained: Boolean = false) {
        try {
            if (client?.isConnected == true) {
                val message = MqttMessage(payload.toByteArray())
                message.qos = 0
                message.isRetained = retained
                client?.publish(topic, message)
            } else {
                logger.warn("MQTT client not connected, cannot publish to $topic")
            }
        } catch (e: Exception) {
            logger.error("Failed to publish MQTT message to $topic: ${e.message}")
        }
    }
}
