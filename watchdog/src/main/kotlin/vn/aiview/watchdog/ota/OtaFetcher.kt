package vn.aiview.watchdog.ota

import org.slf4j.LoggerFactory
import vn.aiview.watchdog.model.OtaConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lấy manifest và gói từ domain phát hành.
 *
 * Ba tính chất bắt buộc cho đường truyền ngoài field, không phải tối ưu cho đẹp:
 *
 *  1. **ETag / If-None-Match** — đêm nào không có bản mới thì tốn ~0 byte. Nhân với số máy
 *     trong fleet nhân 365 đêm, đây là khác biệt giữa "không ai để ý" và "hoá đơn băng thông".
 *
 *  2. **Tải nối tiếp được (HTTP Range)** — đường truyền field hay đứt giữa file 200MB. Không
 *     có Range thì mỗi lần đứt là tải lại từ 0, và một đường mạng đủ tệ sẽ KHÔNG BAO GIỜ tải
 *     xong dù thử cả trăm đêm.
 *
 *  3. **Giới hạn băng thông** — máy này đang ghi hình. Ăn hết đường truyền để tải bản cập nhật
 *     là đánh đổi sai: bản cập nhật chậm một đêm không sao, mất khung hình thì mất thật.
 */
class OtaFetcher(
    private val cfg: OtaConfig,
    private val deviceModel: String,
    private val layout: OtaLayout,
    private val status: OtaStatusWriter,
) {
    private val log = LoggerFactory.getLogger("OtaFetcher")

    private val stateStore = OtaStateStore(layout.stateFile)

    /** URL manifest, scope theo model: .../download/<MODEL>/OTA/manifest.json */
    private fun manifestUrl(): String =
        cfg.manifest_url.ifBlank { "${cfg.base_url.trimEnd('/')}/$deviceModel/OTA/manifest.json" }

    private fun sigUrl(): String = manifestUrl() + ".sig"

    // ── Manifest ─────────────────────────────────────────────────────────────────────────

    sealed interface ManifestResult {
        data class Ok(val verified: OtaVerifier.VerifiedManifest, val etag: String) : ManifestResult
        /** Server nói không đổi — không có bản mới, không tốn byte. */
        data object NotModified : ManifestResult
        /** Không lấy được (mất mạng, 404, timeout). Không phải lỗi bảo mật. */
        data class Unavailable(val reason: String) : ManifestResult
        /** Lấy được nhưng KHÔNG hợp lệ — đáng báo động. */
        data class Rejected(val code: String, val reason: String) : ManifestResult
    }

    fun fetchManifest(keyring: List<OtaVerifier.TrustedKey>): ManifestResult {
        val url = manifestUrl()
        val prevEtag = stateStore.load().lastEtag

        val (body, etag) = try {
            httpGet(url, cacheToken = prevEtag, maxBytes = OtaVerifier.MAX_MANIFEST_BYTES.toLong())
                ?: return ManifestResult.NotModified
        } catch (e: Exception) {
            // Mất mạng là chuyện thường ở field — log info, không phải error, và không alert.
            log.info("MANIFEST_UNAVAILABLE $url: ${e.javaClass.simpleName}: ${e.message}")
            return ManifestResult.Unavailable("${e.javaClass.simpleName}: ${e.message}")
        }

        val sig = try {
            httpGet(sigUrl(), maxBytes = 8192)?.first
                ?: return ManifestResult.Unavailable("server trả 304 cho file .sig (không hợp lý)")
        } catch (e: Exception) {
            return ManifestResult.Unavailable("không lấy được manifest.json.sig: ${e.message}")
        }

        return when (val r = OtaVerifier.verify(body, sig, keyring)) {
            is OtaVerifier.Result.Ok -> {
                log.info("MANIFEST_OK version=${r.verified.manifest.version} etag=$etag")
                ManifestResult.Ok(r.verified, etag)
            }
            is OtaVerifier.Result.Rejected -> ManifestResult.Rejected(r.code, r.reason)
        }
    }

    // ── Gói ──────────────────────────────────────────────────────────────────────────────

    sealed interface DownloadResult {
        data class Ok(val file: File) : DownloadResult
        data class Failed(val reason: String) : DownloadResult
    }

    fun downloadPackage(m: OtaManifest, destDir: File): DownloadResult {
        val url = m.pkg?.url?.takeIf { it.isNotBlank() }
            ?: return DownloadResult.Failed("manifest không khai package.url")
        destDir.mkdirs()
        val name = url.substringAfterLast('/').ifBlank { "nvr-${m.version}.nvrup" }
        val dest = File(destDir, name)

        // KHÔNG có sha256 của gói để đối chiếu ở đây — và đó là chủ ý, không phải thiếu sót:
        // xem OtaPackageRef. Toàn vẹn được bảo đảm ở bước sau (chữ ký manifest trong gói +
        // sha256 từng payload + byte-identity với manifest remote đã verify).
        return try {
            val f = downloadResumable(url, dest, expectedSha256 = null)
            DownloadResult.Ok(f)
        } catch (e: Exception) {
            DownloadResult.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Tải một URL bất kỳ có đối chiếu sha256 (dùng cho fallback tarball của image registry). */
    fun downloadUrl(url: String, dest: File, expectedSha256: String?): File? =
        runCatching { downloadResumable(url, dest, expectedSha256) }
            .onFailure { log.error("DOWNLOAD_FAIL $url: ${it.message}") }
            .getOrNull()

    /**
     * Tải có nối tiếp (Range) + giới hạn băng thông + báo tiến trình.
     *
     * File đang tải dở giữ đuôi `.part`, chỉ rename khi đã đủ và (nếu có) khớp sha256. Nhờ vậy
     * không bao giờ có file tưởng-là-hoàn-chỉnh mà thực ra tải thiếu.
     */
    private fun downloadResumable(url: String, dest: File, expectedSha256: String?): File {
        val part = File(dest.parentFile, "${dest.name}.part")
        var attempt = 0
        val maxAttempts = cfg.download_retries.coerceAtLeast(1)

        while (true) {
            attempt++
            val have = if (part.isFile) part.length() else 0L
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "nvr-watchdog-ota/$deviceModel")
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = conn.responseCode
                val resuming = code == 206
                if (code != 200 && code != 206) {
                    throw IllegalStateException("HTTP $code cho $url")
                }
                if (have > 0 && !resuming) {
                    // Server không hỗ trợ Range — phải tải lại từ đầu, nói rõ để người ta biết
                    // vì sao một file đang tải dở lại quay về 0.
                    log.warn("RANGE_UNSUPPORTED server trả $code, tải lại từ đầu (mất ${have / 1_048_576}MB đã tải)")
                    part.delete()
                }

                val remaining = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
                val total = if (resuming && remaining > 0) have + remaining else remaining
                if (total > 0) {
                    val free = dest.parentFile.usableSpace
                    if (free < total * 2) {
                        throw IllegalStateException(
                            "cần ~${total * 2 / 1_048_576}MB (gói + chỗ giải nén), còn ${free / 1_048_576}MB")
                    }
                }

                status.note("tải ${dest.name}: " +
                    (if (resuming) "nối tiếp từ ${have / 1_048_576}MB" else "bắt đầu") +
                    (if (total > 0) " / ${total / 1_048_576}MB" else ""))

                val bytesPerSec = if (cfg.max_download_mbps > 0) cfg.max_download_mbps * 125_000L else 0L
                var got = have
                var lastReport = System.currentTimeMillis()
                val t0 = System.currentTimeMillis()

                conn.inputStream.use { ins ->
                    java.io.FileOutputStream(part, resuming).buffered(1 shl 16).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var windowStart = System.currentTimeMillis()
                        var windowBytes = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            got += n; windowBytes += n

                            // Giới hạn băng thông: mỗi giây chỉ cho qua bytesPerSec, ngủ phần dư.
                            if (bytesPerSec > 0 && windowBytes >= bytesPerSec) {
                                val elapsed = System.currentTimeMillis() - windowStart
                                if (elapsed < 1000) Thread.sleep(1000 - elapsed)
                                windowStart = System.currentTimeMillis(); windowBytes = 0
                            }

                            // Báo tiến trình mỗi 10s — đủ để UI thấy nhúc nhích, không làm
                            // status.json bị ghi liên tục.
                            if (System.currentTimeMillis() - lastReport > 10_000) {
                                lastReport = System.currentTimeMillis()
                                val pct = if (total > 0) " (${got * 100 / total}%)" else ""
                                status.note("tải ${dest.name}: ${got / 1_048_576}MB$pct")
                            }
                        }
                    }
                }

                if (total > 0 && got < total) {
                    throw IllegalStateException("tải thiếu: ${got}/${total} byte")
                }

                if (expectedSha256 != null) {
                    val actual = OtaVerifier.sha256Hex(part)
                    if (!OtaVerifier.hashEquals(actual, expectedSha256)) {
                        part.delete()   // xoá hẳn: nối tiếp một file sai hash là vô nghĩa
                        throw IllegalStateException(
                            "sha256 lệch (mong ${expectedSha256.take(16)}… được ${actual.take(16)}…)")
                    }
                }

                dest.delete()
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true); part.delete()
                }
                val secs = (System.currentTimeMillis() - t0) / 1000
                log.info("DOWNLOAD_OK ${dest.name} ${got / 1_048_576}MB trong ${secs}s " +
                    "(${if (secs > 0) got / 1_048_576 / secs else 0}MB/s)")
                return dest

            } catch (e: Exception) {
                if (attempt >= maxAttempts) {
                    log.error("DOWNLOAD_GIVE_UP $url sau $attempt lần: ${e.message}")
                    throw e
                }
                // Backoff tăng dần nhưng chặn trần: đường mạng field có thể chập chờn hàng giờ.
                val waitSec = minOf(60L * attempt, 300L)
                log.warn("DOWNLOAD_RETRY lần $attempt/$maxAttempts thất bại (${e.message}) — " +
                    "thử lại sau ${waitSec}s, đã có ${(if (part.isFile) part.length() else 0) / 1_048_576}MB")
                status.note("tải thất bại lần $attempt, thử lại sau ${waitSec}s…")
                Thread.sleep(waitSec * 1000)
            }
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────────────────

    /**
     * GET nhỏ có cache validator. Trả null nếu server đáp 304 (không đổi).
     *
     * [cacheToken] là giá trị lưu từ lần trước, có thể là ETag hoặc Last-Modified — gửi cả hai
     * header. Vì sao cả hai: không phải server nào cũng gửi ETag. `python3 -m http.server`
     * (dùng khi test) chỉ gửi Last-Modified, và một số cấu hình nginx/CDN cũng tắt ETag. Chỉ
     * dựa vào ETag thì những server đó khiến máy tải lại manifest mỗi đêm — 2KB nên không đau,
     * nhưng cũng không có lý do gì để mất.
     *
     * Phân biệt hai loại bằng dấu ngoặc kép: ETag theo RFC luôn có dạng "..." hoặc W/"...".
     *
     * [maxBytes] chặn trước khi cấp bộ nhớ: một server bị chiếm có thể trả 10GB ở URL manifest,
     * và đọc hết vào RAM là hạ luôn máy ghi hình.
     */
    private fun httpGet(url: String, cacheToken: String = "", maxBytes: Long): Pair<ByteArray, String>? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "nvr-watchdog-ota/$deviceModel")
            if (cacheToken.isNotBlank()) {
                if (cacheToken.startsWith("\"") || cacheToken.startsWith("W/")) {
                    setRequestProperty("If-None-Match", cacheToken)
                } else {
                    setRequestProperty("If-Modified-Since", cacheToken)
                }
            }
        }
        val code = conn.responseCode
        if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return null
        if (code !in 200..299) throw IllegalStateException("HTTP $code cho $url")

        val declared = conn.contentLengthLong
        if (declared > maxBytes) throw IllegalStateException(
            "$url khai ${declared} byte, vượt trần $maxBytes")

        val body = conn.inputStream.use { ins ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                if (out.size() + n > maxBytes) throw IllegalStateException(
                    "$url vượt trần $maxBytes byte giữa lúc đọc")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        // Ưu tiên ETag (chính xác hơn: đổi nội dung là đổi ETag, kể cả trong cùng một giây),
        // rơi về Last-Modified khi server không gửi ETag.
        val validator = conn.getHeaderField("ETag")?.takeIf { it.isNotBlank() }
            ?: conn.getHeaderField("Last-Modified")?.takeIf { it.isNotBlank() }
            ?: ""
        return body to validator
    }
}
