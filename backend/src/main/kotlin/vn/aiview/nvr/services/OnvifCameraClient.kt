package vn.aiview.nvr.services

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * Lightweight ONVIF client built on top of the Ktor HTTP client.
 *
 * Instead of pulling in a heavy Java/ONVIF framework, this sends raw SOAP 1.2
 * envelopes (Content-Type: application/soap+xml) straight to the ONVIF
 * `device_service` endpoint.
 *
 * In the My-NVR field deployment the requests are NOT sent to the camera's
 * local IP directly: they go to a `socat` unicast proxy running on the Edge
 * Gateway (Raspberry Pi), which forwards the raw bytes to the camera at the
 * field site. Hence the default endpoint points at the proxy.
 *
 * The class is thread-safe (a single Ktor client is reused) and must be closed
 * when no longer needed to release the underlying connection pool.
 */
class OnvifCameraClient(
    private val username: String,
    private val password: String,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val requestTimeoutMs: Long = 8_000,
) : Closeable {

    private val http: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeoutMs
            connectTimeoutMillis = requestTimeoutMs
            socketTimeoutMillis = requestTimeoutMs
        }
    }

    // Media/PTZ operations live on their own service endpoints (different path than
    // device_service). Discovered lazily via GetCapabilities and cached. Default to
    // the device endpoint so a discovery failure degrades gracefully.
    private val resolveMutex = Mutex()
    @Volatile private var servicesResolved = false
    private var mediaEndpoint: String = endpoint
    private var ptzEndpoint: String = endpoint

    /** scheme://authority of the configured (proxy) endpoint, e.g. http://10.34.24.12:8080 */
    private val proxyAuthority: String = run {
        val scheme = endpoint.substringBefore("://", "http")
        val authority = endpoint.substringAfter("://").substringBefore("/")
        "$scheme://$authority"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * GetDeviceInformation — manufacturer / model / firmware / serial.
     * Requires WS-Security UsernameToken.
     */
    suspend fun getDeviceInformation(): OnvifDeviceDetails {
        val response = call(endpoint, SoapTemplates.getDeviceInformation(securityHeader()))
        return OnvifDeviceDetails(
            manufacturer = extract(response, "Manufacturer") ?: "Unknown",
            model = extract(response, "Model") ?: "Unknown",
            firmwareVersion = extract(response, "FirmwareVersion") ?: "Unknown",
            serialNumber = extract(response, "SerialNumber") ?: "Unknown",
            hardwareId = extract(response, "HardwareId") ?: "Unknown",
        )
    }

    /**
     * GetSystemDateAndTime — does NOT require authentication. Useful as a
     * connectivity / liveness probe through the proxy.
     */
    suspend fun getSystemDateAndTime(): String {
        return call(endpoint, SoapTemplates.getSystemDateAndTime())
    }

    /**
     * GetProfiles — media profile tokens, needed as the `ProfileToken` for any
     * PTZ command. Returned in the camera's advertised order (index 0 is usually
     * the main stream / primary PTZ profile).
     *
     * Posted to the camera's Media-service endpoint (discovered via GetCapabilities),
     * since device_service answers media ops with "Action Not Implemented".
     */
    suspend fun getProfiles(): List<String> {
        ensureServices()
        val response = call(mediaEndpoint, SoapTemplates.getProfiles(securityHeader()))
        return Regex("""<(?:[A-Za-z0-9]+:)?Profiles\b[^>]*\btoken="([^"]+)"""")
            .findAll(response)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /**
     * ContinuousMove (PTZ). Pan/tilt velocity components are normalized to the
     * generic [-1.0, 1.0] velocity space the camera advertises by default.
     */
    suspend fun moveCamera(profileToken: String, pan: Double, tilt: Double, zoom: Double = 0.0) {
        require(profileToken.isNotBlank()) { "profileToken is required for ContinuousMove" }
        ensureServices()
        call(
            ptzEndpoint,
            SoapTemplates.continuousMove(
                authHeader = securityHeader(),
                profileToken = profileToken,
                pan = pan.coerceIn(-1.0, 1.0),
                tilt = tilt.coerceIn(-1.0, 1.0),
                zoom = zoom.coerceIn(-1.0, 1.0),
            )
        )
    }

    /**
     * Stop — halts any ongoing pan/tilt and zoom movement for the profile.
     */
    suspend fun stopCamera(profileToken: String) {
        require(profileToken.isNotBlank()) { "profileToken is required for Stop" }
        ensureServices()
        call(ptzEndpoint, SoapTemplates.stop(securityHeader(), profileToken))
    }

    override fun close() = http.close()

    // ── Service discovery ───────────────────────────────────────────────────────

    /**
     * Resolves the Media and PTZ service endpoints via GetCapabilities (once,
     * cached). The advertised XAddr points at the camera's LAN IP, so its
     * scheme+authority is rewritten to the configured proxy authority while the
     * path (e.g. /onvif/media_service) is preserved and forwarded by socat.
     */
    private suspend fun ensureServices() {
        if (servicesResolved) return
        resolveMutex.withLock {
            if (servicesResolved) return
            try {
                val caps = call(endpoint, SoapTemplates.getCapabilities(securityHeader()))
                extractServiceXAddr(caps, "Media")?.let { mediaEndpoint = rewriteToProxy(it) }
                extractServiceXAddr(caps, "PTZ")?.let { ptzEndpoint = rewriteToProxy(it) }
            } catch (e: Exception) {
                // Leave endpoints at their device-service default; callers will surface
                // any resulting fault. Don't cache failure so a later call can retry.
                throw OnvifException("ONVIF capability discovery failed: ${e.message}", e)
            }
            servicesResolved = true
        }
    }

    /** Extracts the first `<XAddr>` nested under `<category>` (e.g. Media, PTZ). */
    private fun extractServiceXAddr(xml: String, category: String): String? =
        Regex(
            """<(?:[A-Za-z0-9]+:)?$category\b[^>]*>.*?<(?:[A-Za-z0-9]+:)?XAddr>(.*?)</(?:[A-Za-z0-9]+:)?XAddr>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml)?.groupValues?.get(1)?.trim()

    /** Keeps the XAddr path but swaps its scheme+authority for the proxy's. */
    private fun rewriteToProxy(xaddr: String): String {
        val path = xaddr.substringAfter("://").substringAfter("/", "")
        return if (path.isEmpty()) proxyAuthority else "$proxyAuthority/$path"
    }

    // ── Transport ───────────────────────────────────────────────────────────────

    private suspend fun call(url: String, soapXml: String): String {
        val response: HttpResponse = try {
            http.post(url) {
                contentType(ContentType.parse("application/soap+xml; charset=utf-8"))
                header("Accept", "application/soap+xml")
                setBody(soapXml)
            }
        } catch (e: Exception) {
            throw OnvifException("ONVIF transport error to $url: ${e.message}", e)
        }

        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw OnvifException("ONVIF request failed (HTTP ${response.status.value}): ${faultReason(text) ?: text.take(300)}")
        }
        // A 200 can still carry a SOAP Fault (e.g. auth failure on some cameras).
        if (text.contains(":Fault", ignoreCase = true) || text.contains("<Fault", ignoreCase = true)) {
            throw OnvifException("ONVIF SOAP fault: ${faultReason(text) ?: "unknown fault"}")
        }
        return text
    }

    // ── WS-Security UsernameToken (PasswordDigest) ──────────────────────────────

    /**
     * Builds the `<s:Header>` with a WS-Security UsernameToken.
     *
     * PasswordDigest = Base64( SHA-1( nonceBytes + createdBytes + passwordBytes ) )
     *
     * Note: the digest is computed over the RAW (binary) nonce bytes, while the
     * `<Nonce>` element carries the Base64 encoding of those same bytes — this is
     * the behaviour mandated by the WS-Security UsernameToken profile.
     */
    private fun securityHeader(): String {
        val nonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonceB64 = Base64.getEncoder().encodeToString(nonceBytes)
        val created = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString() // ISO 8601, e.g. 2026-06-03T03:44:33Z

        val sha1 = MessageDigest.getInstance("SHA-1").apply {
            update(nonceBytes)
            update(created.toByteArray(Charsets.UTF_8))
            update(password.toByteArray(Charsets.UTF_8))
        }
        val digest = Base64.getEncoder().encodeToString(sha1.digest())

        return """
            <s:Header>
              <wsse:Security s:mustUnderstand="1"
                  xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
                  xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
                <wsse:UsernameToken>
                  <wsse:Username>${escapeXml(username)}</wsse:Username>
                  <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</wsse:Password>
                  <wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceB64</wsse:Nonce>
                  <wsu:Created>$created</wsu:Created>
                </wsse:UsernameToken>
              </wsse:Security>
            </s:Header>
        """.trimIndent()
    }

    // ── Tiny XML helpers (avoid a full XML parser for a handful of leaf tags) ────

    private fun extract(xml: String, tag: String): String? =
        Regex("<(?:[A-Za-z0-9]+:)?$tag>(.*?)</(?:[A-Za-z0-9]+:)?$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()

    private fun faultReason(xml: String): String? =
        extract(xml, "Text") ?: extract(xml, "faultstring") ?: extract(xml, "Reason")

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    companion object {
        // Legacy standalone /api/onvif/{ptz,test-connection} default endpoint. The production path
        // (/api/cameras/{slug}/ptz/*) resolves ip:port dynamically and ignores this. Made overridable
        // via ONVIF_PROXY_ENDPOINT so nothing is hardcoded to one gateway (4-6 Plan A cleanup).
        val DEFAULT_ENDPOINT: String =
            System.getenv("ONVIF_PROXY_ENDPOINT") ?: "http://10.34.24.12:8080/onvif/device_service"
    }
}

/** Result of GetDeviceInformation. */
data class OnvifDeviceDetails(
    val manufacturer: String,
    val model: String,
    val firmwareVersion: String,
    val serialNumber: String,
    val hardwareId: String,
)

/** Raised on transport errors, non-2xx responses, or SOAP faults. */
class OnvifException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Pure-string SOAP 1.2 envelope templates for the ONVIF actions we use.
 * Each builder takes a pre-rendered `<s:Header>` (empty string for unauthenticated calls).
 */
private object SoapTemplates {

    private fun envelope(header: String, body: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
            xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
            xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
            xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl"
            xmlns:tt="http://www.onvif.org/ver10/schema">
        $header
          <s:Body>
        $body
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    fun getSystemDateAndTime(): String =
        envelope(header = "", body = "    <tds:GetSystemDateAndTime/>")

    fun getDeviceInformation(authHeader: String): String =
        envelope(header = authHeader, body = "    <tds:GetDeviceInformation/>")

    fun getCapabilities(authHeader: String): String =
        envelope(header = authHeader, body = "    <tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>")

    fun getProfiles(authHeader: String): String =
        envelope(header = authHeader, body = "    <trt:GetProfiles/>")

    fun continuousMove(authHeader: String, profileToken: String, pan: Double, tilt: Double, zoom: Double): String {
        val panTilt = if (pan != 0.0 || tilt != 0.0) """<tt:PanTilt x="$pan" y="$tilt"/>""" else ""
        val zoomNode = if (zoom != 0.0) """<tt:Zoom x="$zoom"/>""" else ""
        return envelope(
            header = authHeader,
            body = """
                <tptz:ContinuousMove>
                  <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
                  <tptz:Velocity>
                    $panTilt
                    $zoomNode
                  </tptz:Velocity>
                </tptz:ContinuousMove>
            """.trimIndent(),
        )
    }

    fun stop(authHeader: String, profileToken: String): String =
        envelope(
            header = authHeader,
            body = """
                <tptz:Stop>
                  <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
                  <tptz:PanTilt>true</tptz:PanTilt>
                  <tptz:Zoom>true</tptz:Zoom>
                </tptz:Stop>
            """.trimIndent(),
        )
}
