package vn.aiview.nvr.gateway

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import vn.aiview.nvr.utils.AppLogger

@Serializable
data class ZtMember(
    val nodeId: String,
    val authorized: Boolean,
    val ipAssignments: List<String> = emptyList(),
)

/**
 * Talks to the self-hosted ZeroTier controller REST API on the HQ box (ZT_CONTROLLER_URL,
 * e.g. http://192.168.2.106:9993). This is what lets the admin authorize a brand-new gateway
 * straight from the NVR UI instead of opening ztncui: the controller already knows about a
 * member the moment its Pi contacts the network (even while still unauthorized), so the NVR can
 * list those pending members and flip `authorized` for the one the admin approves.
 *
 * Auth: the controller's local API expects header `X-ZT1-Auth: <authtoken.secret>`, carried here
 * via the [GatewayConfig.controllerToken] Docker secret. All NVR-initiated, short timeouts.
 */
object ZtControllerClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(CIO) { engine { requestTimeout = 8_000 } }

    private data class Cfg(val url: String, val nwid: String, val token: String)

    private fun cfg(): Cfg? {
        val url = GatewayConfig.controllerUrl ?: return null
        val nwid = GatewayConfig.controllerNetworkId ?: return null
        val token = GatewayConfig.controllerToken
        if (token.isBlank()) return null
        return Cfg(url.trimEnd('/'), nwid, token)
    }

    /** True when url + network id + token are all present (feature is dormant otherwise). */
    fun isConfigured(): Boolean = cfg() != null

    private fun HttpRequestBuilder.ztAuth(token: String) { header("X-ZT1-Auth", token) }

    /** Every member the controller knows for the fleet network, with its authorized flag. */
    suspend fun listMembers(): List<ZtMember>? {
        val c = cfg() ?: return null
        return withTimeoutOrNull(12_000) {
            runCatching {
                val resp = http.get("${c.url}/controller/network/${c.nwid}/member") { ztAuth(c.token) }
                if (!resp.status.isSuccess()) {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "ZT_LIST_FAIL", "status=${resp.status}")
                    return@runCatching null
                }
                // Response is a JSON object { "<nodeId>": <revision>, ... }
                val ids = json.parseToJsonElement(resp.bodyAsText()).jsonObject.keys
                ids.mapNotNull { getMember(c, it) }
            }.getOrElse {
                AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "ZT_LIST_FAIL", it.message ?: "")
                null
            }
        }
    }

    private suspend fun getMember(c: Cfg, nodeId: String): ZtMember? = runCatching {
        val resp = http.get("${c.url}/controller/network/${c.nwid}/member/$nodeId") { ztAuth(c.token) }
        if (!resp.status.isSuccess()) return@runCatching null
        val o = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        ZtMember(
            nodeId = nodeId,
            authorized = o["authorized"]?.jsonPrimitive?.booleanOrNull ?: false,
            ipAssignments = o["ipAssignments"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
        )
    }.getOrNull()

    /** Authorize a member; the controller then auto-assigns it an IP from the network pool. */
    suspend fun authorize(nodeId: String): Boolean {
        val c = cfg() ?: return false
        return withTimeoutOrNull(8_000) {
            runCatching {
                val resp = http.post("${c.url}/controller/network/${c.nwid}/member/$nodeId") {
                    ztAuth(c.token); contentType(ContentType.Application.Json); setBody("""{"authorized":true}""")
                }
                if (!resp.status.isSuccess())
                    AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "ZT_AUTHORIZE_FAIL", "$nodeId status=${resp.status}")
                resp.status.isSuccess()
            }.getOrElse {
                AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "ZT_AUTHORIZE_FAIL", "$nodeId: ${it.message}")
                false
            }
        } ?: false
    }
}
