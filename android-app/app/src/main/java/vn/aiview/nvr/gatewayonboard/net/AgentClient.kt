package vn.aiview.nvr.gatewayonboard.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to the edge agent over the field LAN using only its no-auth endpoints (`/identity`, `/label`,
 * `/enroll`). The onboarding app holds no fleet token (Tier 0, D4), so it never touches camera/proxy
 * endpoints. `/enroll` points a gateway at a region's ZeroTier network (the id the admin hands out).
 */
class AgentClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    // Aggressive timeouts: scanning probes up to 254 hosts, most of which won't answer.
    private val scanClient = OkHttpClient.Builder()
        .connectTimeout(700, TimeUnit.MILLISECONDS)
        .readTimeout(700, TimeUnit.MILLISECONDS)
        .callTimeout(1200, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    // A known gateway we're submitting to can afford a longer timeout.
    private val actionClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** GET /identity. Returns null on any failure (host not a gateway / unreachable / bad payload). */
    suspend fun identity(host: String, port: Int = DEFAULT_PORT): Identity? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("http://$host:$port/identity").get().build()
        try {
            scanClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                runCatching { json.decodeFromString<Identity>(body) }.getOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** POST /label — set human name/installer/location on the gateway. */
    suspend fun label(
        host: String,
        port: Int,
        name: String,
        installer: String,
        location: String,
    ): Result<Identity> = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(LabelRequest(name.trim(), installer.trim(), location.trim()))
        val req = Request.Builder()
            .url("http://$host:$port/label")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        try {
            actionClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { json.decodeFromString<ErrorResponse>(text).error }
                        .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(Exception(msg))
                }
                Result.success(json.decodeFromString<Identity>(text))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * POST /enroll — point the gateway at a region's ZeroTier network (the 16-hex id the admin gave).
     * No fleet token needed (the network id isn't a secret; the node stays unauthorized until the
     * admin approves it on the NVR). The agent leaves any old network and joins this one.
     */
    suspend fun enroll(
        host: String,
        port: Int,
        networkId: String,
    ): Result<EnrollResponse> = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(EnrollRequest(networkId.trim().lowercase()))
        val req = Request.Builder()
            .url("http://$host:$port/enroll")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        try {
            actionClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { json.decodeFromString<ErrorResponse>(text).error }
                        .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(Exception(msg))
                }
                Result.success(json.decodeFromString<EnrollResponse>(text))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        const val DEFAULT_PORT = 9797
    }
}
