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

    // Reuse the same logic as CameraService.detectLanIp — env var first, then heuristic
    internal fun nvrIp(): String =
        System.getenv("FRIGATE_HOST_IP")?.takeIf { it.isNotBlank() } ?: try {
            NetworkInterface.getNetworkInterfaces()
                .toList().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .filterNot { val n = it.name.lowercase(); n.startsWith("docker") || n.startsWith("br-") || n.startsWith("veth") }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { ip -> ip.startsWith("192.168.") || ip.startsWith("10.") || ip.matches(Regex("^172\\.(1[6-9]|2\\d|3[01])\\..*")) }
                ?: ""
        } catch (_: Exception) { "" }

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
        // Advertise on port 80 (nginx proxies /onvif/ on HTTP — Box uses http://)
        val xAddr = "http://$ip/onvif/device_service"
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
