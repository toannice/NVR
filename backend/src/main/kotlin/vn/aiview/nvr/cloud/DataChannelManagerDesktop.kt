package vn.aiview.nvr.cloud

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import vn.aiview.nvr.utils.AppLogger

object DataChannelManagerDesktop {
    private val client = HttpClient(CIO) {
        engine { requestTimeout = 15_000 }
    }

    suspend fun handleOffer(
        sdp: String,
        offerId: String,
        msgId: String,
        channelId: String,
        targetId: String,
        iceServers: List<IceServer>
    ): String? {
        return try {
            val body = buildJsonObject {
                put("sdp", sdp)
                put("offerId", offerId)
                put("msgId", msgId)
                put("channelId", channelId)
                put("targetId", targetId)
                put("ice_servers", buildJsonArray {
                    iceServers.forEach { ice ->
                        addJsonObject {
                            put("urls", buildJsonArray { ice.urls.forEach { add(it) } })
                            if (ice.username != null) put("username", ice.username)
                            if (ice.credential != null) put("credential", ice.credential)
                        }
                    }
                })
            }
            val response = client.post("http://127.0.0.1:8766/dc/offer") {
                header("Content-Type", "application/json")
                setBody(body.toString())
            }
            val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            result["sdp"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "DC_OFFER_FAIL", e.message ?: "")
            null
        }
    }

    fun handleIceCandidate(candidateJson: String) {
        // For DATA sessions, ICE candidates are forwarded to dc-bridge.
        // dc-bridge uses non-trickle ICE with embedded candidates in answer SDP,
        // so this is a no-op in the current implementation.
        AppLogger.debug(AppLogger.Module.SYSTEM, "CLOUD", "ICE_CANDIDATE_IGNORED", "DATA ICE candidate dropped (non-trickle mode)")
    }
}
