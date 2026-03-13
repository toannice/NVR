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

data class OnvifDeviceInfo(
    val manufacturer: String,
    val model: String,
    val rtspUrl: String
)

object OnvifSoapClient {

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
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
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
        // Regex to extract value inside XML tags ignoring namespaces e.g. <tt:Manufacturer>Value</tt:Manufacturer>
        val regex = Regex("<(?:[a-zA-Z0-9]+:)?$tag>(.*?)</(?:[a-zA-Z0-9]+:)?$tag>")
        return regex.find(xml)?.groupValues?.get(1)?.trim()
    }

    private fun extractAttribute(xml: String, tag: String, attr: String): String? {
        val regex = Regex("<(?:[a-zA-Z0-9]+:)?$tag[^>]*$attr=\"([^\"]+)\"")
        return regex.find(xml)?.groupValues?.get(1)?.trim()
    }

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
        
        // Ensure local IP is used if XAddr returns weird internal IPs
        val mediaUrl = rawMediaUrl.replace(Regex("http://[0-9\\.]+(:[0-9]+)?"), "http://$ip:$port")

        // 3. Get Profiles
        val getProfilesReq = buildSoapRequest(authHeader, "<trt:GetProfiles/>")
        val profilesRes = sendSoapPost(mediaUrl, getProfilesReq)
        
        // Find the first Profile token
        val firstProfileToken = extractAttribute(profilesRes, "Profiles", "token") ?: throw Exception("No media profiles found")

        // 4. Get Stream URI
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

        // Make sure it contains credentials
        if (password != null && !streamUri.contains("@")) {
            streamUri = streamUri.replace("rtsp://", "rtsp://$username:$password@")
        }

        return OnvifDeviceInfo(manufacturer, model, streamUri)
    }
}
