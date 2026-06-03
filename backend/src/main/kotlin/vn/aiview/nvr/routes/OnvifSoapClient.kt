package vn.aiview.nvr.routes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

data class OnvifDeviceInfo(
    val manufacturer: String,
    val model: String,
    val rtspUrl: String,
    val profileToken: String = "",
    val subRtspUrl: String? = null,
)

object OnvifSoapClient {
    private val ptzCache = ConcurrentHashMap<String, Pair<String, String>>()

    private fun getOnvifAuthHeader(username: String, password: String?): String {
        if (password.isNullOrEmpty()) {
            return ""
        }
        val nonceBytes = ByteArray(16)
        SecureRandom().nextBytes(nonceBytes)
        val nonceStr = Base64.getEncoder().encodeToString(nonceBytes)

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val createdStr = sdf.format(Date())

        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(nonceBytes)
        digest.update(createdStr.toByteArray())
        digest.update(password.toByteArray())
        val pwDigestStr = Base64.getEncoder().encodeToString(digest.digest())

        return """
            <s:Header>
                <Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
                    <UsernameToken>
                        <Username>$username</Username>
                        <Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$pwDigestStr</Password>
                        <Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceStr</Nonce>
                        <Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$createdStr</Created>
                    </UsernameToken>
                </Security>
            </s:Header>
        """.trimIndent()
    }

    private fun buildSoapRequest(authHeader: String, body: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
            xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
            xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
            xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl"
            xmlns:tt="http://www.onvif.org/ver10/schema">
$authHeader
<s:Body>$body</s:Body>
</s:Envelope>""".trimIndent()
    }

    private suspend fun sendSoapPost(urlStr: String, requestXml: String): String = withContext(Dispatchers.IO) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000

        conn.outputStream.write(requestXml.toByteArray())

        val status = conn.responseCode
        if (status in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }
            throw Exception("SOAP request failed with HTTP $status: $errorBody")
        }
    }

    private fun extractValue(xml: String, tag: String): String? {
        val regex = Regex("<(?:[a-zA-Z0-9]+:)?$tag>(.*?)</(?:[a-zA-Z0-9]+:)?$tag>")
        return regex.find(xml)?.groupValues?.get(1)?.trim()
    }

    private fun extractAttribute(xml: String, tag: String, attr: String): String? {
        val regex = Regex("<(?:[a-zA-Z0-9]+:)?$tag[^>]*$attr=\"([^\"]+)\"")
        return regex.find(xml)?.groupValues?.get(1)?.trim()
    }

    private fun extractAllAttributes(xml: String, tag: String, attr: String): List<String> {
        val regex = Regex("<(?:[a-zA-Z0-9]+:)?$tag[^>]*$attr=\"([^\"]+)\"")
        return regex.findAll(xml).map { it.groupValues[1].trim() }.toList()
    }

    // ── Existing: Device info + stream URI ────────────────────────────────────

    suspend fun getDeviceInfoAndStream(ip: String, port: Int = 80, username: String, password: String?): OnvifDeviceInfo {
        val baseUrl = "http://$ip:$port/onvif/device_service"
        val authHeader = getOnvifAuthHeader(username, password)

        // 1. Get Device Information
        val getDeviceInfoReq = buildSoapRequest(authHeader, "<tds:GetDeviceInformation/>")
        var manufacturer = "Unknown"
        var model = "Unknown"
        try {
            val deviceInfoRes = sendSoapPost(baseUrl, getDeviceInfoReq)
            manufacturer = extractValue(deviceInfoRes, "Manufacturer") ?: "Unknown"
            model = extractValue(deviceInfoRes, "Model") ?: "Unknown"
        } catch (e: Exception) {
            throw Exception("Failed to get device info, check credentials or ONVIF port. Error: ${e.message}")
        }

        // 2. Get Capabilities to find Media Service URI
        val getCapReq = buildSoapRequest(authHeader, "<tds:GetCapabilities><tds:Category>Media</tds:Category></tds:GetCapabilities>")
        val capRes = sendSoapPost(baseUrl, getCapReq)
        val rawMediaUrl = extractValue(capRes, "XAddr") ?: throw Exception("Media service not found on device")
        val mediaUrl = rawMediaUrl.replace(Regex("http://[0-9\\.]+(:[0-9]+)?"), "http://$ip:$port")

        // 3. Get Profiles (all tokens for main + sub stream)
        val getProfilesReq = buildSoapRequest(authHeader, "<trt:GetProfiles/>")
        val profilesRes = sendSoapPost(mediaUrl, getProfilesReq)
        val allTokens = extractAllAttributes(profilesRes, "Profiles", "token")
        val firstProfileToken = allTokens.firstOrNull() ?: throw Exception("No media profiles found")

        // 4. Get Main Stream URI
        val getStreamUriReq = buildSoapRequest(authHeader, """
            <trt:GetStreamUri>
                <trt:StreamSetup>
                    <tt:Stream>RTP-Unicast</tt:Stream>
                    <tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport>
                </trt:StreamSetup>
                <trt:ProfileToken>$firstProfileToken</trt:ProfileToken>
            </trt:GetStreamUri>
        """.trimIndent())

        val streamUriRes = sendSoapPost(mediaUrl, getStreamUriReq)
        var streamUri = extractValue(streamUriRes, "Uri") ?: throw Exception("Could not retrieve Stream URI")

        if (password != null && !streamUri.contains("@")) {
            streamUri = streamUri.replace("rtsp://", "rtsp://$username:$password@")
        }

        // 5. Get Sub Stream URI from 2nd profile (non-fatal if missing)
        var subRtspUrl: String? = null
        val secondToken = allTokens.getOrNull(1)
        if (secondToken != null) {
            try {
                val subAuthHeader = getOnvifAuthHeader(username, password)
                val subUriReq = buildSoapRequest(subAuthHeader, """
                    <trt:GetStreamUri>
                        <trt:StreamSetup>
                            <tt:Stream>RTP-Unicast</tt:Stream>
                            <tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport>
                        </trt:StreamSetup>
                        <trt:ProfileToken>$secondToken</trt:ProfileToken>
                    </trt:GetStreamUri>
                """.trimIndent())
                val subUriRes = sendSoapPost(mediaUrl, subUriReq)
                var subUri = extractValue(subUriRes, "Uri")
                if (subUri != null) {
                    if (password != null && !subUri.contains("@")) {
                        subUri = subUri.replace("rtsp://", "rtsp://$username:$password@")
                    }
                    subRtspUrl = subUri
                }
            } catch (_: Exception) {}
        }

        return OnvifDeviceInfo(manufacturer, model, streamUri, firstProfileToken, subRtspUrl)
    }

    // ── NEW: PTZ helpers ──────────────────────────────────────────────────────

    suspend fun getPtzServiceUrlAndTokenCached(ip: String, port: Int, username: String, password: String?): Pair<String, String> {
        val cacheKey = "$ip:$port:$username"
        return ptzCache.getOrPut(cacheKey) {
            getPtzServiceUrlAndToken(ip, port, username, password)
        }
    }

    /**
     * Returns (ptzServiceUrl, profileToken) needed to send PTZ commands.
     */
    suspend fun getPtzServiceUrlAndToken(ip: String, port: Int, username: String, password: String?): Pair<String, String> {
        val baseUrl = "http://$ip:$port/onvif/device_service"
        val authHeader = getOnvifAuthHeader(username, password)

        // PTZ service URL
        val capReq = buildSoapRequest(authHeader, "<tds:GetCapabilities><tds:Category>PTZ</tds:Category></tds:GetCapabilities>")
        val capRes = sendSoapPost(baseUrl, capReq)
        val rawPtzUrl = extractValue(capRes, "XAddr") ?: throw Exception("PTZ service not found on this device")
        val ptzUrl = rawPtzUrl.replace(Regex("http://[0-9\\.]+(:[0-9]+)?"), "http://$ip:$port")

        // Profile token (from Media service)
        val authHeader2 = getOnvifAuthHeader(username, password)
        val mediaCapReq = buildSoapRequest(authHeader2, "<tds:GetCapabilities><tds:Category>Media</tds:Category></tds:GetCapabilities>")
        val mediaCapRes = sendSoapPost(baseUrl, mediaCapReq)
        val rawMediaUrl = extractValue(mediaCapRes, "XAddr") ?: throw Exception("Media service not found on device")
        val mediaUrl = rawMediaUrl.replace(Regex("http://[0-9\\.]+(:[0-9]+)?"), "http://$ip:$port")

        val authHeader3 = getOnvifAuthHeader(username, password)
        val profilesReq = buildSoapRequest(authHeader3, "<trt:GetProfiles/>")
        val profilesRes = sendSoapPost(mediaUrl, profilesReq)
        val profileToken = extractAttribute(profilesRes, "Profiles", "token") ?: throw Exception("No media profiles found")

        return Pair(ptzUrl, profileToken)
    }

    /**
     * Returns true if the device advertises a PTZ service AND has at least one real PTZ node
     * (i.e. a <PTZNode token="..."> element with content, not just an empty service stub).
     * Returns a map with hasPtz, hasPanTilt, and hasZoom boolean flags.
     */
    suspend fun checkPtzCapability(ip: String, port: Int, username: String, password: String?): Map<String, Boolean> {
        val defaultRet = mapOf("hasPtz" to false, "hasPanTilt" to false, "hasZoom" to false)
        return try {
            val baseUrl = "http://$ip:$port/onvif/device_service"
            val authHeader = getOnvifAuthHeader(username, password)

            // Step 1: check GetCapabilities PTZ XAddr
            val capReq = buildSoapRequest(authHeader, "<tds:GetCapabilities><tds:Category>PTZ</tds:Category></tds:GetCapabilities>")
            val capRes = sendSoapPost(baseUrl, capReq)
            val ptzXAddr = extractValue(capRes, "XAddr")
            if (ptzXAddr.isNullOrBlank()) return defaultRet

            val ptzUrl = ptzXAddr.replace(Regex("http://[0-9\\.]+(:[0-9]+)?"), "http://$ip:$port")

            // Step 2: call GetNodes and check for actual PTZNode elements with a token attribute
            val authHeader2 = getOnvifAuthHeader(username, password)
            val nodesReq = buildSoapRequest(authHeader2, "<tptz:GetNodes/>")
            val nodesRes = sendSoapPost(ptzUrl, nodesReq)

            // A real PTZ node always has a token attribute: <tptz:PTZNode token="...">
            // Check for the pattern to avoid matching namespace declarations or empty responses
            val hasPtzNodeWithToken = Regex("""<[^>:]*:?PTZNode[^>]+token\s*=\s*["'][^"']+["']""", RegexOption.IGNORE_CASE)
                .containsMatchIn(nodesRes)

            val hasPanTilt = nodesRes.contains("PanTilt", ignoreCase = true)
            val hasZoom = nodesRes.contains("Zoom", ignoreCase = true)

            if (hasPtzNodeWithToken && (hasPanTilt || hasZoom)) {
                mapOf("hasPtz" to true, "hasPanTilt" to hasPanTilt, "hasZoom" to hasZoom)
            } else {
                defaultRet
            }
        } catch (e: Exception) {
            defaultRet
        }
    }

    suspend fun continuousMove(
        ptzUrl: String, profileToken: String,
        username: String, password: String?,
        pan: Double, tilt: Double, zoom: Double
    ) {
        val authHeader = getOnvifAuthHeader(username, password)
        // Omit `space` entirely! Let the camera use its default Velocity space.
        val panTiltNode = if (pan != 0.0 || tilt != 0.0) """<tt:PanTilt x="$pan" y="$tilt"/>""" else ""
        val zoomNode = if (zoom != 0.0) """<tt:Zoom x="$zoom"/>""" else ""
        
        val body = """
            <tptz:ContinuousMove>
                <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
                <tptz:Velocity>
                    $panTiltNode
                    $zoomNode
                </tptz:Velocity>
            </tptz:ContinuousMove>
        """.trimIndent()
        sendSoapPost(ptzUrl, buildSoapRequest(authHeader, body))
    }

    /**
     * Sends Stop to halt any ongoing PTZ movement.
     */
    suspend fun stopMove(
        ptzUrl: String, profileToken: String,
        username: String, password: String?
    ) {
        val authHeader = getOnvifAuthHeader(username, password)
        val body = """
            <tptz:Stop>
                <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
                <tptz:PanTilt>true</tptz:PanTilt>
                <tptz:Zoom>true</tptz:Zoom>
            </tptz:Stop>
        """.trimIndent()
        sendSoapPost(ptzUrl, buildSoapRequest(authHeader, body))
    }
}
