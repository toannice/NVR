package vn.aiview.nvr.gateway

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import vn.aiview.nvr.utils.AppLogger

// ── DTOs returned by nvr-edge-agent (see edge-agent/) ──────────────────────────

@Serializable
data class EdgeCamera(
    val localIp: String,
    val name: String = "",
    val onvifPort: Int = 0,
    val rtspPort: Int = 0,
)

@Serializable
data class EdgeHealth(
    val status: String = "",
    val gatewayId: String = "",
    val gatewayName: String = "",
    val zerotierIp: String = "",
    val ztAddress: String = "",
    val ztJoined: Boolean = false,
    val ztOnline: Boolean = false,
    val installer: String = "",
    val location: String = "",
    val fieldSubnet: String = "",
    val agentPort: Int = 0,
    val ramMb: Int = 0,
    val uptimeSec: Int = 0,
    val nginxOk: Boolean = false,
    val cameras: List<EdgeCamera> = emptyList(),
)

@Serializable
data class EdgeDevice(
    val ip: String,
    val name: String = "",
    val hardware: String = "",
    val xaddrs: List<String> = emptyList(),
)

@Serializable private data class DevicesEnvelope(val devices: List<EdgeDevice> = emptyList())
@Serializable private data class CamerasEnvelope(val cameras: List<EdgeCamera> = emptyList())

/**
 * HTTP client to a passive edge agent over ZeroTier. The NVR is always the client — the agent
 * never calls us. Short timeouts + one retry because the ZeroTier link is intermittent.
 * Bearer auth uses the shared fleet token ([GatewayConfig.token]).
 */
object EdgeAgentClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(CIO) { engine { requestTimeout = 8_000 } }

    private fun base(ip: String, port: Int) = "http://$ip:$port"

    private suspend fun <T> withRetry(label: String, quiet: Boolean = false, block: suspend () -> T?): T? {
        repeat(2) { attempt ->
            val r = runCatching { block() }.getOrElse { e ->
                // `quiet` suppresses logging for subnet sweeps, where most hosts are not agents.
                if (attempt == 1 && !quiet) AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "AGENT_CALL_FAIL", "$label: ${e.message}")
                null
            }
            if (r != null) return r
        }
        return null
    }

    suspend fun health(ip: String, port: Int, token: String, timeoutMs: Long = 4_000, quiet: Boolean = false): EdgeHealth? =
        withRetry("health $ip", quiet) {
            withTimeoutOrNull(timeoutMs) {
                val resp = http.get("${base(ip, port)}/health") { bearer(token) }
                if (!resp.status.isSuccess()) null else json.decodeFromString<EdgeHealth>(resp.bodyAsText())
            }
        }

    suspend fun discover(ip: String, port: Int, token: String, timeoutMs: Long = 9_000): List<EdgeDevice>? =
        withRetry("discover $ip") {
            withTimeoutOrNull(timeoutMs) {
                val resp = http.post("${base(ip, port)}/discovery") {
                    bearer(token); contentType(ContentType.Application.Json); setBody("{}")
                }
                if (!resp.status.isSuccess()) null else json.decodeFromString<DevicesEnvelope>(resp.bodyAsText()).devices
            }
        }

    /** Idempotent: creates (or returns existing) ONVIF+RTSP proxy ports for the field camera. */
    suspend fun createProxy(ip: String, port: Int, token: String, localIp: String, name: String, timeoutMs: Long = 6_000): EdgeCamera? =
        withRetry("createProxy $ip/$localIp") {
            withTimeoutOrNull(timeoutMs) {
                val resp = http.post("${base(ip, port)}/camera") {
                    bearer(token); contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(EdgeCamera.serializer(), EdgeCamera(localIp = localIp, name = name)))
                }
                if (!resp.status.isSuccess()) {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "CREATE_PROXY_FAIL", "$localIp: ${resp.status} ${resp.bodyAsText().take(160)}")
                    null
                } else json.decodeFromString<EdgeCamera>(resp.bodyAsText())
            }
        }

    suspend fun listCameras(ip: String, port: Int, token: String, timeoutMs: Long = 4_000): List<EdgeCamera>? =
        withRetry("listCameras $ip") {
            withTimeoutOrNull(timeoutMs) {
                val resp = http.get("${base(ip, port)}/cameras") { bearer(token) }
                if (!resp.status.isSuccess()) null else json.decodeFromString<CamerasEnvelope>(resp.bodyAsText()).cameras
            }
        }

    suspend fun deleteProxy(ip: String, port: Int, token: String, localIp: String, timeoutMs: Long = 5_000): Boolean =
        withRetry("deleteProxy $ip/$localIp") {
            withTimeoutOrNull(timeoutMs) {
                http.delete("${base(ip, port)}/camera?localIp=$localIp") { bearer(token) }.status.isSuccess()
            }
        } ?: false

    private fun HttpRequestBuilder.bearer(token: String) {
        if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
    }
}
