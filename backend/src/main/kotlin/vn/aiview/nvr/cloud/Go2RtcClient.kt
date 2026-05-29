package vn.aiview.nvr.cloud

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.withTimeoutOrNull
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

    suspend fun deleteStream(name: String) {
        val encodedName = URLEncoder.encode(name, Charsets.UTF_8)
        try {
            http.delete("$BASE/api/streams?src=$encodedName")
        } catch (_: Exception) {
        }
    }
}
