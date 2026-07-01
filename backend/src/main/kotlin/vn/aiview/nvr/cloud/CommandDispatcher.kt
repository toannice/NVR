package vn.aiview.nvr.cloud

import kotlinx.serialization.json.*
import vn.aiview.nvr.cloud.handlers.CameraCommandHandler
import vn.aiview.nvr.cloud.handlers.DeviceCommandHandler
import vn.aiview.nvr.cloud.handlers.PlaybackCommandHandler
import vn.aiview.nvr.utils.AppLogger

object CommandDispatcher {

    fun dispatch(signalData: JsonObject, data: JsonObject?) {
        // STEP 3a: Navigate envelope (data.data.envelope or data.envelope)
        val envelope = data?.get("data")?.jsonObject?.get("envelope")?.jsonObject
            ?: data?.get("envelope")?.jsonObject
        if (envelope == null) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "DISPATCH_NO_ENVELOPE",
                "dataKeys=${data?.keys} — cannot find envelope")
            return
        }
        val header = envelope["header"]?.jsonObject
        val body = envelope["body"]?.jsonObject
        if (header == null || body == null) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "DISPATCH_BAD_ENVELOPE",
                "header=${header != null} body=${body != null} envelopeKeys=${envelope.keys}")
            return
        }

        val msgId = signalData["message_id"]?.jsonPrimitive?.content ?: ""
        val senderId = signalData["source_id"]?.jsonPrimitive?.content ?: ""
        val component = header["component"]?.jsonPrimitive?.content ?: ""

        // STEP 3b: Extract func from body
        val (func, bodyData) = extractFuncAndData(body)
        if (func.isEmpty()) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "DISPATCH_NO_FUNC",
                "component=$component bodyKeys=${body.keys}")
            return
        }

        // STEP 3c: Route and respond
        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "CMD_RECV", "component=$component func=$func msgId=$msgId")
        val result = routeCommand(component, func, bodyData)
        sendResponse(component, func, result, msgId, senderId)
    }

    fun dispatchDataChannel(rawJson: String, reply: (String) -> Unit) {
        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "DC_CMD_IN", rawJson.take(600))
        try {
            val json = Json.parseToJsonElement(rawJson).jsonObject
            // Try all possible envelope locations in priority order:
            //   1. signal.manager MQTT format: data.data.envelope (C# server reuses MQTT format over DC)
            //   2. DataChannel simple format: data.envelope
            //   3. Bare envelope
            val envelope = json["data"]?.jsonObject?.get("data")?.jsonObject?.get("envelope")?.jsonObject
                ?: json["data"]?.jsonObject?.get("envelope")?.jsonObject
                ?: json["envelope"]?.jsonObject
                ?: run {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "DC_NO_ENVELOPE",
                        "keys=${json.keys} data_keys=${json["data"]?.jsonObject?.keys}")
                    reply("{}")
                    return
                }
            val header = envelope["header"]?.jsonObject ?: run {
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "DC_NO_HEADER", "envelopeKeys=${envelope.keys}")
                reply("{}")
                return
            }
            val body = envelope["body"]?.jsonObject ?: run {
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "DC_NO_BODY", "envelopeKeys=${envelope.keys}")
                reply("{}")
                return
            }
            val component = header["component"]?.jsonPrimitive?.content ?: ""
            // Echo message_id back in response header so cloud can match response to camera/sourceToken
            val messageId = header["message_id"]?.jsonPrimitive?.contentOrNull
            val (func, bodyData) = extractFuncAndData(body)
            if (func.isEmpty()) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "DC_NO_FUNC", "component=$component bodyKeys=${body.keys}")
                reply("{}")
                return
            }

            AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "DC_CMD_RECV", "component=$component func=$func")
            val result = routeCommand(component, func, bodyData)
            val body2 = buildResponsePayload(component, func, result)
            // Use bare {"envelope":{...}} to match the format the C# server sends and parses
            val response = buildJsonObject {
                put("envelope", buildJsonObject {
                    put("header", buildJsonObject {
                        put("component", component)
                        if (messageId != null) put("message_id", messageId)
                    })
                    put("body", body2)
                })
            }
            AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "DC_CMD_OUT", response.toString().take(2000))
            reply(response.toString())
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "DC_DISPATCH_ERROR", e.message ?: "")
            reply("{}")
        }
    }

    private fun extractFuncAndData(body: JsonObject): Pair<String, JsonObject> {
        // Standard format: body.func = "get_channels", body.params = {channel_id}, body.data = {host, port...}
        val funcField = body["func"]?.jsonPrimitive?.contentOrNull
        if (!funcField.isNullOrEmpty()) {
            val data = body["data"]?.let { it as? JsonObject } ?: buildJsonObject {}
            val params = body["params"]?.let { it as? JsonObject } ?: buildJsonObject {}
            // Merge params into data so handlers can read channel_id from one place
            val merged = buildJsonObject {
                data.entries.forEach { (k, v) -> put(k, v) }
                params.entries.forEach { (k, v) -> put(k, v) }
            }
            return funcField to merged
        }
        // B02 server format: body = {"get_channels": {...params...}}
        val firstKey = body.keys.firstOrNull() ?: return "" to buildJsonObject {}
        val data = body[firstKey]?.let { it as? JsonObject } ?: buildJsonObject {}
        return firstKey to data
    }

    private fun routeCommand(component: String, func: String, data: JsonObject): JsonObject {
        return try {
            // Playback control rides the PLAYBACK peer's DataChannel and the cloud tags it
            // under the "ps_service" component (B01) — route by func so it always reaches the
            // playback handler regardless of which component header the cloud sends.
            if (func == "post_select_record" || func == "post_select_record_timestamp" ||
                func == "post_control_record") {
                return PlaybackCommandHandler.handle(func, data)
            }
            when (component) {
                "device", "device_service" -> DeviceCommandHandler.handle(func, data)
                "channel_service", "media_service" -> CameraCommandHandler.handle(func, data)
                "playback_service", "ps_service" -> PlaybackCommandHandler.handle(func, data)
                else -> errorResponse("E0009", "Component not supported: $component")
            }
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "COMMAND_ERROR", "$component/$func: ${e.message}")
            errorResponse("E0007", e.message ?: "Internal error")
        }
    }

    private fun sendResponse(component: String, func: String, result: JsonObject, msgId: String, senderId: String) {
        val topic = SignalingManager.mainPubTopic
        val isSuccess = result["status"]?.jsonPrimitive?.booleanOrNull != false
        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "STEP4_SEND_RESP",
            "func=${func}_response status=$isSuccess topic=${topic.takeLast(30)} body=${buildResponsePayload(component, func, result)}")

        val payload = buildJsonObject {
            put("context", "signal.manager")
            put("type", "send-action.response")
            put("source_id", SignalingManager.myPeerId)
            put("target_id", senderId)
            put("message_id", msgId)
            put("auth", buildJsonObject { put("type", "inherit") })
            put("version", "1.0.2.3")
            put("data_encode_type", "none")
            put("data", buildJsonObject {
                put("envelope", buildJsonObject {
                    put("header", buildJsonObject { put("component", component) })
                    put("body", buildResponsePayload(component, func, result))
                })
            })
        }
        SignalingManager.publish(topic, payload.toString())
    }

    fun buildResponsePayload(component: String, func: String, data: JsonObject): JsonObject {
        val isSuccess = data["status"]?.jsonPrimitive?.booleanOrNull != false
        val errorCode = if (isSuccess) "I200" else (data["error_code"]?.jsonPrimitive?.content ?: "E0007")
        val message = if (isSuccess) "${func.replace("_response", "")}.success" else "${func.replace("_response", "")}.error"
        val innerData = data["data"] ?: buildJsonObject { put("status", "") }

        return buildJsonObject {
            put("func", "${func}_response")
            put("status", isSuccess)
            put("error_code", errorCode)
            put("message", message)
            put("data", buildJsonObject {
                put("status", isSuccess)
                put("error_code", errorCode)
                put("message", message)
                put("data", innerData)
            })
        }
    }

    fun successResponse(data: JsonElement = buildJsonObject { put("status", "") }): JsonObject =
        buildJsonObject {
            put("status", true)
            put("data", data)
        }

    fun errorResponse(code: String, message: String): JsonObject =
        buildJsonObject {
            put("status", false)
            put("error_code", code)
            put("message", message)
            put("data", buildJsonObject { put("status", "") })
        }
}
