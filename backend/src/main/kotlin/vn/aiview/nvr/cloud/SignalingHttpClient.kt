package vn.aiview.nvr.cloud

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import vn.aiview.nvr.utils.AppLogger
import java.util.Base64

data class MqttConnectionInfo(
    val peerId: String,
    val serverUri: String,
    val clientId: String,
    val mqttUsername: String,
    val mqttPassword: String,
    val subTopic: String,
    val pubTopic: String
)

data class IceServer(
    val urls: List<String>,
    val username: String?,
    val credential: String?
)

data class CloudConfigData(
    val domain: String,
    val port: Int,
    val username: String,
    val password: String,
    val serial: String,
    val enabled: Boolean
)

object SignalingHttpClient {
    private val client = HttpClient(CIO) {
        engine { requestTimeout = 10_000 }
    }

    suspend fun fetchMqttConnectionInfo(config: CloudConfigData): MqttConnectionInfo? {
        val domain = config.domain.trimEnd('/')
        val url = if (domain.startsWith("http")) "$domain:${config.port}/p2p/api/sample/client/connect"
                  else "https://$domain:${config.port}/p2p/api/sample/client/connect"
        val credentials = Base64.getEncoder().encodeToString("${config.username}:${config.password}".toByteArray())

        repeat(5) { attempt ->
            try {
                val requestBody = buildJsonObject {
                    put("context", "signal.connect")
                    put("type", "new")
                    put("data", buildJsonObject { put("reference_id", config.serial) })
                    put("auth", buildJsonObject { put("type", "inherit") })
                    put("version", "1.0.2.3")
                    put("data_encode_type", "none")
                }
                val response = client.post(url) {
                    header("Authorization", "Basic $credentials")
                    header("Content-Type", "application/json")
                    setBody(requestBody.toString())
                }
                val responseText = response.bodyAsText()
                val body = Json.parseToJsonElement(responseText).jsonObject

                // Actual response structure:
                // { "peer_id": "...", "transports": { "transport_infos": [{ "type":"mqtt", "data": {
                //     "connect": { "server":"...", "auth":{"data":{"username","password"}},
                //                  "client_id":"...", "options":{"transport_client_id":"..."} },
                //     "functions": [{"function":"send","path":"..."}, {"function":"received","path":"..."}]
                // }}]}}
                val peerId = body["peer_id"]?.jsonPrimitive?.content
                    ?: throw IllegalStateException("Missing peer_id in response: $responseText")

                val mqttTransport = body["transports"]?.jsonObject
                    ?.get("transport_infos")?.jsonArray
                    ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "mqtt" }
                    ?.jsonObject
                    ?: throw IllegalStateException("No MQTT transport in response")

                val mqttData = mqttTransport["data"]?.jsonObject
                    ?: throw IllegalStateException("Missing mqtt data")
                val connect = mqttData["connect"]?.jsonObject
                    ?: throw IllegalStateException("Missing connect block")
                val authData = connect["auth"]?.jsonObject?.get("data")?.jsonObject
                val functions = mqttData["functions"]?.jsonArray
                    ?: throw IllegalStateException("Missing functions array")

                val subTopic = functions.firstOrNull {
                    it.jsonObject["function"]?.jsonPrimitive?.content == "received"
                }?.jsonObject?.get("path")?.jsonPrimitive?.content
                    ?: throw IllegalStateException("Missing received function path")

                val pubTopic = functions.firstOrNull {
                    it.jsonObject["function"]?.jsonPrimitive?.content == "send"
                }?.jsonObject?.get("path")?.jsonPrimitive?.content
                    ?: throw IllegalStateException("Missing send function path")

                val clientId = connect["options"]?.jsonObject?.get("transport_client_id")?.jsonPrimitive?.content
                    ?: connect["client_id"]?.jsonPrimitive?.content
                    ?: peerId

                return MqttConnectionInfo(
                    peerId = peerId,
                    serverUri = connect["server"]?.jsonPrimitive?.content
                        ?: throw IllegalStateException("Missing server URI"),
                    clientId = clientId,
                    mqttUsername = authData?.get("username")?.jsonPrimitive?.content ?: "",
                    mqttPassword = authData?.get("password")?.jsonPrimitive?.content ?: "",
                    subTopic = subTopic,
                    pubTopic = pubTopic
                )
            } catch (e: Exception) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "HANDSHAKE_RETRY",
                    "Attempt ${attempt + 1}/5 failed [${e::class.simpleName}]: ${e.message}")
                if (attempt < 4) delay(5_000)
            }
        }
        return null
    }

    suspend fun fetchIceServers(config: CloudConfigData): List<IceServer> {
        val credentials = Base64.getEncoder().encodeToString("${config.username}:${config.password}".toByteArray())
        return try {
            val d = config.domain.trimEnd('/')
            val iceUrl = if (d.startsWith("http")) "$d:${config.port}/p2p/api/sample/server/resource/ices"
                         else "https://$d:${config.port}/p2p/api/sample/server/resource/ices"
            val response = client.get(iceUrl) {
                header("Authorization", "Basic $credentials")
            }
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            body.map { item ->
                val obj = item.jsonObject
                IceServer(
                    urls = obj["urls"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    username = obj["username"]?.jsonPrimitive?.contentOrNull,
                    credential = obj["credential"]?.jsonPrimitive?.contentOrNull
                )
            }
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "ICE_FETCH_FAIL", e.message ?: "")
            emptyList()
        }
    }
}
