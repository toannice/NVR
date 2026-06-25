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

/** One gateway awaiting "Cấp phép" on the HQ signing service (the Nebula analog of a pending ZT member). */
@Serializable
data class NebulaPending(
    val token: String,
    val name: String = "",
    val overlayIp: String = "",
    val status: String = "pending",
)

/** Result of minting a region enrollment token — the "mã khu vực" handed to the field worker. */
@Serializable
data class NebulaMintResult(
    val token: String,
    val region: String = "",
    val lighthouse: String = "",
    val lighthouseOverlayIp: String = "",
)

/** HQ signing-service `/healthz` payload (no auth) — lets the NVR/watchdog tell if the control plane is alive. */
@Serializable
data class NebulaHqHealth(
    val ok: Boolean = false,
    val lighthouse: String = "",
    val pending: Int = 0,
    val uptimeSec: Long = 0,
)

/**
 * Talks to the HQ Nebula signing service (NEBULA_CA_URL, e.g. http://203.0.113.10:9443) — the
 * ZeroTier→Nebula replacement for [ZtControllerClient]. Instead of authorizing a member on the
 * ZeroTier controller, the admin "Cấp phép" here SIGNS the gateway's cert: the signing service runs
 * `nebula-cert sign -in-pub` and the polling agent picks up its cert. Same one-click UX on the NVR.
 *
 * Auth: admin endpoints carry `Authorization: Bearer <token>` from [GatewayConfig.nebulaSignToken]
 * (Docker secret secrets/nebula_sign_token). All NVR-initiated, short timeouts. The feature is dormant
 * (every call returns null/false) unless [GatewayConfig.nebulaEnabled].
 */
object NebulaCaClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(CIO) { engine { requestTimeout = 8_000 } }

    private data class Cfg(val url: String, val token: String)

    private fun cfg(): Cfg? {
        val url = GatewayConfig.nebulaCaUrl ?: return null
        val token = GatewayConfig.nebulaSignToken
        if (token.isBlank()) return null
        return Cfg(url.trimEnd('/'), token)
    }

    fun isConfigured(): Boolean = cfg() != null

    private fun HttpRequestBuilder.bearer(token: String) { header("Authorization", "Bearer $token") }

    /**
     * Poll the HQ signing service `/healthz` (no auth) so the NVR knows whether the control plane is up.
     * Only needs [GatewayConfig.nebulaCaUrl] (not the admin token), so it works even before the sign
     * token is set. Returns null on any failure (unreachable / bad payload) — caller treats null as down.
     */
    suspend fun health(): NebulaHqHealth? {
        val url = GatewayConfig.nebulaCaUrl?.trimEnd('/') ?: return null
        return withTimeoutOrNull(6_000) {
            runCatching {
                val resp = http.get("$url/healthz")
                if (!resp.status.isSuccess()) null
                else json.decodeFromString(NebulaHqHealth.serializer(), resp.bodyAsText())
            }.getOrElse {
                AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "NEBULA_HEALTHZ_FAIL", it.message ?: "")
                null
            }
        }
    }

    /** Gateways that submitted an enrollment and await admin approval (NVR "Chờ cấp phép"). */
    suspend fun listPending(): List<NebulaPending>? {
        val c = cfg() ?: return null
        return withTimeoutOrNull(12_000) {
            runCatching {
                val resp = http.get("${c.url}/pending") { bearer(c.token) }
                if (!resp.status.isSuccess()) {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "NEBULA_PENDING_FAIL", "status=${resp.status}")
                    return@runCatching null
                }
                val arr = json.parseToJsonElement(resp.bodyAsText()).jsonObject["pending"]?.jsonArray ?: return@runCatching emptyList()
                arr.map { json.decodeFromJsonElement(NebulaPending.serializer(), it) }
            }.getOrElse {
                AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "NEBULA_PENDING_FAIL", it.message ?: "")
                null
            }
        }
    }

    /** Approve ("Cấp phép") a gateway by its enrollment token → the signing service signs its cert. */
    suspend fun approve(token: String): Boolean {
        val c = cfg() ?: return false
        return withTimeoutOrNull(12_000) {
            runCatching {
                val resp = http.post("${c.url}/approve") {
                    bearer(c.token); contentType(ContentType.Application.Json); setBody("""{"token":"$token"}""")
                }
                if (!resp.status.isSuccess())
                    AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "NEBULA_APPROVE_FAIL", "status=${resp.status}")
                resp.status.isSuccess()
            }.getOrElse {
                AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "NEBULA_APPROVE_FAIL", it.message ?: "")
                false
            }
        } ?: false
    }

    /**
     * Revoke ("Thu hồi") a gateway by its overlay IP (the NVR tracks gateways by overlay IP, not the
     * enrollment token). The signing service flags the request revoked (refusing renew/re-enroll so the
     * short-lived cert lapses) and blocklists its cert fingerprint. Returns false if Nebula isn't wired
     * or the call fails — the caller still removes the gateway locally so a down HQ never blocks revoke.
     */
    suspend fun revoke(overlayIp: String): Boolean {
        val c = cfg() ?: return false
        return withTimeoutOrNull(12_000) {
            runCatching {
                val resp = http.post("${c.url}/revoke") {
                    bearer(c.token); contentType(ContentType.Application.Json); setBody("""{"overlayIp":"$overlayIp"}""")
                }
                if (!resp.status.isSuccess())
                    AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "NEBULA_REVOKE_FAIL", "status=${resp.status}")
                resp.status.isSuccess()
            }.getOrElse {
                AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "NEBULA_REVOKE_FAIL", it.message ?: "")
                false
            }
        } ?: false
    }

    /** Reject + drop a pending enrollment (frees its token for a retry). */
    suspend fun reject(token: String): Boolean {
        val c = cfg() ?: return false
        return withTimeoutOrNull(8_000) {
            runCatching {
                http.post("${c.url}/reject") {
                    bearer(c.token); contentType(ContentType.Application.Json); setBody("""{"token":"$token"}""")
                }.status.isSuccess()
            }.getOrElse { false }
        } ?: false
    }

    /**
     * Mint a one-time region enrollment token (the "mã khu vực"). The admin hands the returned token +
     * lighthouse address to the field worker, who types it into the app's /enroll.
     */
    suspend fun mintToken(region: String, ipCidr: String, caName: String = "", groups: String = "gateways", ttlMinutes: Int = 0): NebulaMintResult? {
        val c = cfg() ?: return null
        val body = buildJsonObject {
            put("region", region); put("ipCidr", ipCidr); put("groups", groups)
            if (caName.isNotBlank()) put("caName", caName)
            if (ttlMinutes > 0) put("ttlMinutes", ttlMinutes)
        }
        return withTimeoutOrNull(8_000) {
            runCatching {
                val resp = http.post("${c.url}/tokens") {
                    bearer(c.token); contentType(ContentType.Application.Json); setBody(body.toString())
                }
                if (!resp.status.isSuccess()) {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "NEBULA_MINT_FAIL", "status=${resp.status}")
                    return@runCatching null
                }
                json.decodeFromString(NebulaMintResult.serializer(), resp.bodyAsText())
            }.getOrElse {
                AppLogger.warn(AppLogger.Module.SYSTEM, "GATEWAY", "NEBULA_MINT_FAIL", it.message ?: "")
                null
            }
        }
    }
}
