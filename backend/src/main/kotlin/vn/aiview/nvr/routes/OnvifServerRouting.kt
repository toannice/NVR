package vn.aiview.nvr.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.services.OnvifServerService
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * NVR acts as an ONVIF server so Box (or any ONVIF client) can discover it via WS-Discovery
 * and pull the camera list via GetProfiles/GetStreamUri — no custom integration API needed.
 *
 * Endpoints are HTTP (no auth wrapper) so Box can call them on port 80 via nginx proxy.
 * RTSP streams point to go2rtc on port 8554 — accessible from the LAN.
 */
fun Route.onvifServerRouting() {
    route("/onvif") {
        post("/device_service") { handleNvrSoap(call) }
        post("/media_service")  { handleNvrSoap(call) }
    }
}

private suspend fun handleNvrSoap(call: ApplicationCall) {
    val body = call.receiveText()
    val ip   = OnvifServerService.nvrIp()
    val rtspBase = "rtsp://$ip:8554"
    val deviceUrl = "http://$ip/onvif/device_service"
    val mediaUrl  = "http://$ip/onvif/media_service"

    val (soapBody, action) = when {
        body.contains("GetSystemDateAndTime")  -> systemDateAndTime() to "GetSystemDateAndTimeResponse"
        body.contains("GetDeviceInformation")  -> deviceInformation()  to "GetDeviceInformationResponse"
        body.contains("GetCapabilities")       -> capabilities(deviceUrl, mediaUrl) to "GetCapabilitiesResponse"
        body.contains("GetScopes")             -> scopes() to "GetScopesResponse"
        body.contains("GetServices")           -> services(deviceUrl, mediaUrl) to "GetServicesResponse"
        body.contains("GetProfiles")           -> profiles(rtspBase) to "GetProfilesResponse"
        body.contains("GetVideoSources")       -> videoSources() to "GetVideoSourcesResponse"
        body.contains("GetStreamUri")          -> {
            val token = Regex("<[a-zA-Z0-9:]*ProfileToken>(.*?)</[a-zA-Z0-9:]*ProfileToken>")
                .find(body)?.groupValues?.get(1) ?: ""
            val slug = token.removePrefix("profile_")
            streamUri(slug, rtspBase) to "GetStreamUriResponse"
        }
        else -> return call.respond(HttpStatusCode.BadRequest, "Unknown SOAP action")
    }

    call.respondText(soapEnvelope(soapBody, action), ContentType.parse("application/soap+xml; charset=utf-8"))
}

// ── SOAP response builders ─────────────────────────────────────────────────────

private fun systemDateAndTime(): String {
    val now = ZonedDateTime.now(ZoneOffset.UTC)
    return """<tds:GetSystemDateAndTimeResponse xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
    xmlns:tt="http://www.onvif.org/ver10/schema">
  <tds:SystemDateAndTime>
    <tt:DateTimeType>NTP</tt:DateTimeType>
    <tt:DaylightSavings>false</tt:DaylightSavings>
    <tt:TimeZone><tt:TZ>UTC</tt:TZ></tt:TimeZone>
    <tt:UTCDateTime>
      <tt:Time><tt:Hour>${now.hour}</tt:Hour><tt:Minute>${now.minute}</tt:Minute><tt:Second>${now.second}</tt:Second></tt:Time>
      <tt:Date><tt:Year>${now.year}</tt:Year><tt:Month>${now.monthValue}</tt:Month><tt:Day>${now.dayOfMonth}</tt:Day></tt:Date>
    </tt:UTCDateTime>
  </tds:SystemDateAndTime>
</tds:GetSystemDateAndTimeResponse>"""
}

private fun deviceInformation() =
    """<tds:GetDeviceInformationResponse xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
  <tds:Manufacturer>Bkav AI</tds:Manufacturer>
  <tds:Model>AIView NVR</tds:Model>
  <tds:FirmwareVersion>1.0.0</tds:FirmwareVersion>
  <tds:SerialNumber>aiview-nvr-001</tds:SerialNumber>
  <tds:HardwareId>AIView-NVR-1.0</tds:HardwareId>
</tds:GetDeviceInformationResponse>"""

private fun capabilities(deviceUrl: String, mediaUrl: String) =
    """<tds:GetCapabilitiesResponse xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
    xmlns:tt="http://www.onvif.org/ver10/schema">
  <tds:Capabilities>
    <tt:Device><tt:XAddr>$deviceUrl</tt:XAddr></tt:Device>
    <tt:Media><tt:XAddr>$mediaUrl</tt:XAddr><tt:StreamingCapabilities><tt:RTPMulticast>false</tt:RTPMulticast><tt:RTP_TCP>true</tt:RTP_TCP><tt:RTP_RTSP_TCP>true</tt:RTP_RTSP_TCP></tt:StreamingCapabilities></tt:Media>
  </tds:Capabilities>
</tds:GetCapabilitiesResponse>"""

private fun scopes() =
    """<tds:GetScopesResponse xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
    xmlns:tt="http://www.onvif.org/ver10/schema">
  <tds:Scopes><tt:ScopeDef>Fixed</tt:ScopeDef><tt:ScopeItem>onvif://www.onvif.org/name/AIView%20NVR</tt:ScopeItem></tds:Scopes>
  <tds:Scopes><tt:ScopeDef>Fixed</tt:ScopeDef><tt:ScopeItem>onvif://www.onvif.org/hardware/AIView-NVR</tt:ScopeItem></tds:Scopes>
  <tds:Scopes><tt:ScopeDef>Fixed</tt:ScopeDef><tt:ScopeItem>onvif://www.onvif.org/type/NetworkVideoTransmitter</tt:ScopeItem></tds:Scopes>
  <tds:Scopes><tt:ScopeDef>Fixed</tt:ScopeDef><tt:ScopeItem>aiview://type/NVRProxy</tt:ScopeItem></tds:Scopes>
</tds:GetScopesResponse>"""

private fun services(deviceUrl: String, mediaUrl: String) =
    """<tds:GetServicesResponse xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
  <tds:Service>
    <tds:Namespace>http://www.onvif.org/ver10/device/wsdl</tds:Namespace>
    <tds:XAddr>$deviceUrl</tds:XAddr>
    <tds:Version><tds:Major>2</tds:Major><tds:Minor>0</tds:Minor></tds:Version>
  </tds:Service>
  <tds:Service>
    <tds:Namespace>http://www.onvif.org/ver10/media/wsdl</tds:Namespace>
    <tds:XAddr>$mediaUrl</tds:XAddr>
    <tds:Version><tds:Major>2</tds:Major><tds:Minor>0</tds:Minor></tds:Version>
  </tds:Service>
</tds:GetServicesResponse>"""

private fun profiles(rtspBase: String): String {
    data class CamRow(val slug: String, val name: String, val channelId: String?)
    val cameras = transaction {
        Cameras.selectAll().map { CamRow(it[Cameras.slug], it[Cameras.displayName], it[Cameras.channelId]) }
    }
    val profileXml = cameras.joinToString("\n") { cam ->
        val token  = "profile_${cam.slug}"
        val vsToken = "vs_${cam.slug}"
        """  <trt:Profiles token="$token" fixed="true" xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
               xmlns:tt="http://www.onvif.org/ver10/schema">
    <tt:Name>${cam.name.xmlEscape()}</tt:Name>
    <tt:VideoSourceConfiguration token="$vsToken">
      <tt:Name>${cam.name.xmlEscape()}</tt:Name>
      <tt:UseCount>1</tt:UseCount>
      <tt:SourceToken>$vsToken</tt:SourceToken>
      <tt:Bounds x="0" y="0" width="1920" height="1080"/>
    </tt:VideoSourceConfiguration>
    <tt:VideoEncoderConfiguration token="vec_${cam.slug}">
      <tt:Name>${cam.name.xmlEscape()}</tt:Name>
      <tt:UseCount>1</tt:UseCount>
      <tt:Encoding>H264</tt:Encoding>
      <tt:Resolution><tt:Width>1920</tt:Width><tt:Height>1080</tt:Height></tt:Resolution>
      <tt:RateControl><tt:FrameRateLimit>25</tt:FrameRateLimit><tt:EncodingInterval>1</tt:EncodingInterval><tt:BitrateLimit>4096</tt:BitrateLimit></tt:RateControl>
    </tt:VideoEncoderConfiguration>
  </trt:Profiles>"""
    }
    return """<trt:GetProfilesResponse xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
$profileXml
</trt:GetProfilesResponse>"""
}

private fun videoSources(): String {
    data class CamRow(val slug: String, val name: String)
    val cameras = transaction { Cameras.selectAll().map { CamRow(it[Cameras.slug], it[Cameras.displayName]) } }
    val xml = cameras.joinToString("\n") { cam ->
        """  <trt:VideoSources token="vs_${cam.slug}" xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
               xmlns:tt="http://www.onvif.org/ver10/schema">
    <tt:Framerate>25</tt:Framerate>
    <tt:Resolution><tt:Width>1920</tt:Width><tt:Height>1080</tt:Height></tt:Resolution>
  </trt:VideoSources>"""
    }
    return """<trt:GetVideoSourcesResponse xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
$xml
</trt:GetVideoSourcesResponse>"""
}

private fun streamUri(slug: String, rtspBase: String): String {
    // go2rtc restream: rtsp://<nvr-ip>:8554/<slug>
    val rtsp = "$rtspBase/$slug"
    return """<trt:GetStreamUriResponse xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
    xmlns:tt="http://www.onvif.org/ver10/schema">
  <trt:MediaUri>
    <tt:Uri>$rtsp</tt:Uri>
    <tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>
    <tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>
    <tt:Timeout>PT0S</tt:Timeout>
  </trt:MediaUri>
</trt:GetStreamUriResponse>"""
}

private fun soapEnvelope(body: String, action: String) =
    """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
            xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing">
  <s:Header>
    <a:Action>http://www.onvif.org/ver10/device/wsdl/$action</a:Action>
  </s:Header>
  <s:Body>
$body
  </s:Body>
</s:Envelope>"""

private fun String.xmlEscape() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
