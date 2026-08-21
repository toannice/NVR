package vn.aiview.nvr.services

import kotlinx.coroutines.*
import kotlinx.coroutines.isActive
import vn.aiview.nvr.utils.AppLogger
import java.net.*
import java.util.UUID

object OnvifServerService {

    private var job: Job? = null
    private const val DISCOVERY_PORT = 3702
    private const val MULTICAST_GROUP = "239.255.255.250"

    fun start() {
        job = CoroutineScope(Dispatchers.IO).launch { runLoop() }
        AppLogger.info(AppLogger.Module.SYSTEM, "ONVIF_SERVER", "START", "WS-Discovery responder started on UDP $DISCOVERY_PORT")
    }

    fun stop() {
        job?.cancel()
    }

    // Single source of truth for "reachable LAN IP" — see utils/NetUtils.
    internal fun nvrIp(): String = vn.aiview.nvr.utils.NetUtils.lanIp()

    /**
     * HTTP port nginx serves /onvif/ on. Was the implicit 80; moved to 9000 (2026-07-29).
     * Must match `listen` in nginx/nginx.conf — Box calls the XAddr we advertise verbatim.
     */
    internal fun onvifHttpPort(): Int =
        System.getenv("ONVIF_HTTP_PORT")?.toIntOrNull() ?: 9000

    /** "http://<lan-ip>:<port>" base for every XAddr we hand out. */
    internal fun httpBase(): String = "http://${nvrIp()}:${onvifHttpPort()}"

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                MulticastSocket(DISCOVERY_PORT).use { socket ->
                    socket.soTimeout = 5_000
                    val group = InetAddress.getByName(MULTICAST_GROUP)
                    socket.joinGroup(group)
                    AppLogger.info(AppLogger.Module.SYSTEM, "ONVIF_SERVER", "LISTENING", "Joined $MULTICAST_GROUP:$DISCOVERY_PORT")
                    val buf = ByteArray(8192)
                    while (currentCoroutineContext().isActive) {
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            socket.receive(pkt)
                            val body = String(pkt.data, 0, pkt.length)
                            if (body.contains("Probe") && !body.contains("ProbeMatches")) {
                                val msgId = Regex("<[^:]+:MessageID[^>]*>(.*?)</[^:]+:MessageID>")
                                    .find(body)?.groupValues?.get(1) ?: "uuid:${UUID.randomUUID()}"
                                val response = buildProbeMatch(msgId).toByteArray()
                                socket.send(DatagramPacket(response, response.size, pkt.address, pkt.port))
                                AppLogger.info(AppLogger.Module.SYSTEM, "ONVIF_SERVER", "PROBE_MATCH", "Replied to ${pkt.address.hostAddress}:${pkt.port}")
                            }
                        } catch (_: SocketTimeoutException) { /* loop */ }
                    }
                    socket.leaveGroup(group)
                }
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive) {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "ONVIF_SERVER", "ERROR", "WS-Discovery error: ${e.message} — retry in 10s")
                    delay(10_000)
                }
            }
        }
    }

    private fun buildProbeMatch(relatesTo: String): String {
        val ip = nvrIp()
        // Advertise the nginx HTTP port (ONVIF_HTTP_PORT, mặc định 9000 — nginx proxy /onvif/ qua HTTP)
        val xAddr = "${httpBase()}/onvif/device_service"
        return """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
            xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
            xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
            xmlns:dn="http://www.onvif.org/ver10/network/wsdl"
            xmlns:aiview="http://aiview.vn/onvif">
  <s:Header>
    <a:MessageID>uuid:${UUID.randomUUID()}</a:MessageID>
    <a:RelatesTo>$relatesTo</a:RelatesTo>
    <a:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:To>
    <a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</a:Action>
  </s:Header>
  <s:Body>
    <d:ProbeMatches>
      <d:ProbeMatch>
        <a:EndpointReference><a:Address>urn:aiview:nvr:${ip.replace(".", "-")}</a:Address></a:EndpointReference>
        <d:Types>dn:NetworkVideoTransmitter aiview:NVRProxy</d:Types>
        <d:Scopes>
          onvif://www.onvif.org/type/NetworkVideoTransmitter
          onvif://www.onvif.org/name/AIView%20NVR
          onvif://www.onvif.org/hardware/AIView-NVR
          aiview://type/NVRProxy
        </d:Scopes>
        <d:XAddrs>$xAddr</d:XAddrs>
        <d:MetadataVersion>1</d:MetadataVersion>
      </d:ProbeMatch>
    </d:ProbeMatches>
  </s:Body>
</s:Envelope>"""
    }
}
