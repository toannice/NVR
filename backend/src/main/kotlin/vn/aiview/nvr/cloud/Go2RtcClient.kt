package vn.aiview.nvr.cloud

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import vn.aiview.nvr.utils.AppLogger
import java.net.URLEncoder

object Go2RtcClient {
    private const val BASE = "http://127.0.0.1:1984"
    private val http = HttpClient(CIO) { engine { requestTimeout = 5_000 } }

    suspend fun registerStreamSync(name: String, src: String, timeoutMs: Long = 5_000): Boolean {
        val encodedSrc = URLEncoder.encode(src, Charsets.UTF_8)
        val encodedName = URLEncoder.encode(name, Charsets.UTF_8)
        val ok = withTimeoutOrNull(timeoutMs) {
            try {
                val resp: HttpResponse = http.put("$BASE/api/streams?name=$encodedName&src=$encodedSrc")
                resp.status.isSuccess()
            } catch (e: Exception) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "GO2RTC", "REGISTER_FAIL", "$name: ${e.message}")
                false
            }
        } ?: false
        AppLogger.info(AppLogger.Module.SYSTEM, "GO2RTC", "REGISTER", "name=$name ok=$ok")
        return ok
    }

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * GET /api/streams → returns the set of stream names that currently have an ACTIVE producer
     * (a source connection delivering frames). Used by StreamOnlineTracker to derive `online`.
     *
     * go2rtc shape: { "<name>": { "producers": [ {...} ], "consumers": [...] }, ... }
     * "live" = the `producers` array is non-empty (go2rtc only lists a producer once the upstream
     * source is connected). Returns an empty set on any error so callers degrade to "offline".
     */
    suspend fun liveStreamNames(): Set<String> {
        return try {
            val resp: HttpResponse = http.get("$BASE/api/streams")
            if (!resp.status.isSuccess()) return emptySet()
            val body = resp.bodyAsText()
            val root = lenientJson.parseToJsonElement(body).jsonObject
            buildSet {
                for ((name, info) in root) {
                    val producers = info.jsonObject["producers"]?.jsonArray ?: continue
                    if (producers.isNotEmpty()) add(name)
                }
            }
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "GO2RTC", "LIST_FAIL", e.message ?: "")
            emptySet()
        }
    }

    suspend fun deleteStream(name: String) {
        val encodedName = URLEncoder.encode(name, Charsets.UTF_8)
        try {
            http.delete("$BASE/api/streams?src=$encodedName")
        } catch (_: Exception) {
        }
    }
}
