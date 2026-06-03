package vn.aiview.nvr.cloud.handlers

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.cloud.CommandDispatcher
import vn.aiview.nvr.cloud.DeviceConstants
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.routes.OnvifSoapClient
import vn.aiview.nvr.services.CameraService
import vn.aiview.nvr.utils.AppLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object CameraCommandHandler {
    fun handle(func: String, data: JsonObject): JsonObject = when (func) {
        "get_channels" -> getChannels()
        "post_channels_verify" -> postChannelsVerify(data)
        "post_channels", "put_channels" -> postOrPutChannels(func, data)
        "del_channels" -> delChannels(data)
        "get_discovery", "post_discovery", "refresh_discovery" -> getDiscovery()
        "get_discoverydevices" -> getDiscovery()
        "get_videosources" -> getVideoSources()
        "get_profiles_channel" -> getProfilesChannel(data)
        else -> CommandDispatcher.errorResponse("E0008", "Function not supported: $func")
    }

    // ── channel_service ──────────────────────────────────────────────────────

    private fun getChannels(): JsonObject {
        // Ensure every camera row has a channel_id before returning.
        CameraService.backfillChannelIds()
        val cameras = transaction {
            Cameras.selectAll().toList()
        }
        val channels = buildJsonArray {
            cameras.forEach { row ->
                val chId = row[Cameras.channelId] ?: return@forEach
                val ptz = row[Cameras.hasPtz] || row[Cameras.ptzEnabled]
                val mainToken = row[Cameras.mainToken].ifBlank { buildMainToken(chId) }
                val subToken = row[Cameras.subToken].ifBlank { buildSubToken(chId) }
                addJsonObject {
                    put("channelID", chId)
                    put("status", "online")
                    put("isConnected", true)
                    putJsonObject("xAdrr") {
                        put("host", row[Cameras.ip] ?: "")
                        put("port", row[Cameras.onvifPort].toLong())
                        put("https", 0)
                        put("rtspPort", 554)
                    }
                    putJsonObject("scopes") {
                        put("location", "")
                        put("name", row[Cameras.displayName].ifBlank { "Camera $chId" })
                        put("hardware", "")
                        put("type", "video_encoder")
                        put("mac", row[Cameras.macAddress] ?: "")
                        put("manufacture", row[Cameras.manufacturer])
                        put("model", row[Cameras.model])
                        put("firmwareVersion", "")
                        put("serialNumber", "")
                    }
                    putJsonObject("mediaProfile") {
                        put("tokenVS", buildVideoSourceToken(chId))
                        putJsonArray("listTokenMP") {
                            addJsonObject {
                                put("name", "MediaProfile_Channel${chId}_MainStream")
                                put("token", mainToken)
                                put("nodeToken", "")
                                put("isPtz", ptz); put("isZoom", ptz)
                                put("videoEncodingSupport", "H264"); put("isLive", true)
                            }
                            addJsonObject {
                                put("name", "MediaProfile_Channel${chId}_SubStream")
                                put("token", subToken)
                                put("nodeToken", "")
                                put("isPtz", ptz); put("isZoom", ptz)
                                put("videoEncodingSupport", "H264"); put("isLive", true)
                            }
                        }
                    }
                    put("peerID", "")
                    put("snapShotUrl", "")
                    put("snapShotUrl_Private", "")
                    put("isPtz", ptz)
                    put("isPlayback", true)
                    put("channelStatus", 0)
                }
            }
        }
        return buildJsonObject {
            put("status", true)
            put("message", "get.channels.success")
            put("error_code", "")
            putJsonObject("data") {
                put("maxChannelNum", DeviceConstants.NUMBER_OF_CHANNEL)
                put("channels", channels)
            }
        }
    }

    private fun postChannelsVerify(data: JsonObject): JsonObject {
        val host = data["host"]?.jsonPrimitive?.content
            ?: return CommandDispatcher.errorResponse("E0007", "Missing host")
        val port = data["port"]?.jsonPrimitive?.intOrNull ?: 80
        val user = data["username"]?.jsonPrimitive?.content ?: "admin"
        val pass = data["password"]?.jsonPrimitive?.content ?: ""
        return runBlocking {
            try {
                withTimeout(5_000) { OnvifSoapClient.getDeviceInfoAndStream(host, port, user, pass) }
                crudSuccess("post.device.success")
            } catch (e: Exception) {
                CommandDispatcher.errorResponse("E0007", "ONVIF probe failed: ${e.message}")
            }
        }
    }

    private fun postOrPutChannels(func: String, data: JsonObject): JsonObject {
        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "CHANNEL_ADD_PAYLOAD",
            "func=$func keys=${data.keys} raw=${data.toString().take(500)}")
        val channelId = data["channel_id"]?.jsonPrimitive?.content
            ?: data["channelID"]?.jsonPrimitive?.content
            ?: return CommandDispatcher.errorResponse("E0007", "Missing channel_id")
        val host = data["host"]?.jsonPrimitive?.content
            ?: return CommandDispatcher.errorResponse("E0007", "Missing host")
        val port = data["port"]?.jsonPrimitive?.intOrNull ?: 80
        val user = data["username"]?.jsonPrimitive?.content ?: "admin"
        val pass = data["password"]?.jsonPrimitive?.content ?: ""
        val name = listOf("name", "manufacture")
            .mapNotNull { data[it]?.jsonPrimitive?.content?.takeIf(String::isNotEmpty) }
            .firstOrNull() ?: "Camera $channelId"
        val manufacture = data["manufacture"]?.jsonPrimitive?.content ?: "Unknown"

        return runBlocking {
            val applied = CameraService.applyAndPersistFromOnvif(
                channelId = channelId,
                host = host,
                port = port,
                user = user,
                pass = pass,
                displayName = name,
                manufacturer = manufacture,
            ).getOrElse {
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "CHANNEL_ADD_FAIL", it.message ?: "")
                return@runBlocking CommandDispatcher.errorResponse("E0007", it.message ?: "Failed to add camera")
            }
            val successMsg = if (func == "post_channels") "post.device.success" else "put.device.success"
            crudSuccessWithFrigate(successMsg, applied.frigateRestarted)
        }
    }

    private fun delChannels(data: JsonObject): JsonObject {
        val channelId = data["channel_id"]?.jsonPrimitive?.content
            ?: data["channelID"]?.jsonPrimitive?.content
            ?: return CommandDispatcher.errorResponse("E0007", "Missing channel_id")

        return runBlocking {
            CameraService.removeAndPersist(channelId).getOrElse {
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "CHANNEL_DEL_FAIL", it.message ?: "")
                // idempotent — still respond success per spec FR-017
            }
            crudSuccess("delete.device.success")
        }
    }

    private fun getDiscovery(): JsonObject {
        val devices = buildJsonArray {
            try {
                wsDiscovery().forEach { ip ->
                    addJsonObject {
                        putJsonObject("scopes") {
                            put("hardware", ""); put("location", ""); put("mac", "")
                            put("manufacture", ""); put("name", ip); put("type", "video_encoder")
                        }
                        putJsonObject("xAdrr") { put("host", ip); put("https", 0); put("port", 80L) }
                    }
                }
            } catch (_: Exception) {}
        }
        return buildJsonObject {
            put("status", true); put("message", "get.discovery.success"); put("error_code", "")
            putJsonObject("data") { put("device", devices) }
        }
    }

    // ── media_service ────────────────────────────────────────────────────────

    private fun getVideoSources(): JsonObject {
        val cameras = transaction { Cameras.selectAll().toList() }
        return buildJsonObject {
            put("status", true); put("message", "get.videosources.success"); put("error_code", "")
            putJsonObject("data") {
                putJsonArray("videoSources") {
                    cameras.forEach { row ->
                        val chId = row[Cameras.channelId] ?: return@forEach
                        addJsonObject {
                            put("channelID", chId)
                            put("token", buildVideoSourceToken(chId))
                            put("framerate", 25.0)
                            putJsonObject("resolution") { put("width", 1920); put("height", 1080) }
                            putJsonObject("imaging") {
                                put("brightness", 50); put("colorSaturation", 50); put("contrast", 50)
                                putJsonObject("focus") {
                                    put("autoFocusMode", "AUTO"); put("defaultSpeed", 0)
                                    put("nearLimit", 0); put("farLimit", 0)
                                }
                                put("irCutFilter", "AUTO"); put("sharpness", 50)
                                putJsonObject("wideDynamicRange") { put("mode", "OFF"); put("level", 0) }
                                putJsonObject("whiteBalance") { put("mode", "AUTO"); put("crGain", 0); put("cbGain", 0) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getProfilesChannel(data: JsonObject): JsonObject {
        val channelId = data["channel_id"]?.jsonPrimitive?.content
            ?: data["channelID"]?.jsonPrimitive?.content ?: ""
        val row = transaction { Cameras.selectAll().where { Cameras.channelId eq channelId }.firstOrNull() }
        val mainToken = row?.get(Cameras.mainToken)?.ifBlank { buildMainToken(channelId) } ?: buildMainToken(channelId)
        val subToken = row?.get(Cameras.subToken)?.ifBlank { buildSubToken(channelId) } ?: buildSubToken(channelId)
        val ccc = channelId.padStart(3, '0')
        val vsToken = buildVideoSourceToken(channelId)
        val vscToken = "V_SRC_CFG_${ccc}01"
        return buildJsonObject {
            put("status", true); put("message", "get.channelprofiles.success"); put("error_code", "")
            putJsonObject("data") {
                putJsonArray("profiles") {
                    // MainStream
                    addJsonObject {
                        put("name", "MediaProfile_Channel${channelId}_MainStream")
                        put("token", mainToken); put("channelID", channelId); put("fixed", false)
                        putJsonObject("videoSourceConfiguration") {
                            put("name", "VideoSourceConfig_Channel$channelId")
                            put("channelID", channelId); put("token", vscToken); put("sourceToken", vsToken)
                            putJsonObject("bounds") { put("x", 0); put("y", 0); put("width", 1920); put("height", 1080) }
                        }
                        putJsonObject("videoEncoderConfiguration") {
                            put("name", "VideoEncoderConfig_Channel${channelId}_MainStream")
                            put("token", vscToken); put("encoding", "H264")
                            putJsonObject("h264") { put("govLength", 25); put("h264Profile", "Main") }
                            putJsonObject("resolution") { put("width", 1920); put("height", 1080) }
                            put("quality", 50)
                            putJsonObject("rateControl") { put("frameRateLimit", 25); put("encodingInterval", 1); put("bitrateLimit", 4096000) }
                            putJsonObject("multicast") {
                                putJsonObject("address") { put("type", "IPv4"); put("IPv4Address", "0.0.0.0") }
                                put("port", 0); put("TTL", 0); put("autoStart", false)
                            }
                            put("sessionTimeout", 0)
                        }
                    }
                    // SubStream
                    addJsonObject {
                        put("name", "MediaProfile_Channel${channelId}_SubStream")
                        put("token", subToken); put("channelID", channelId); put("fixed", false)
                        putJsonObject("videoSourceConfiguration") {
                            put("name", "VideoSourceConfig_Channel$channelId")
                            put("channelID", channelId); put("token", vscToken); put("sourceToken", vsToken)
                            putJsonObject("bounds") { put("x", 0); put("y", 0); put("width", 1920); put("height", 1080) }
                        }
                        putJsonObject("videoEncoderConfiguration") {
                            put("name", "VideoEncoderConfig_Channel${channelId}_SubStream")
                            put("token", "V_SRC_CFG_${ccc}02"); put("encoding", "H264")
                            putJsonObject("h264") { put("govLength", 25); put("h264Profile", "Main") }
                            putJsonObject("resolution") { put("width", 640); put("height", 480) }
                            put("quality", 50)
                            putJsonObject("rateControl") { put("frameRateLimit", 15); put("encodingInterval", 1); put("bitrateLimit", 1024000) }
                            putJsonObject("multicast") {
                                putJsonObject("address") { put("type", "IPv4"); put("IPv4Address", "0.0.0.0") }
                                put("port", 0); put("TTL", 0); put("autoStart", false)
                            }
                            put("sessionTimeout", 0)
                        }
                    }
                }
            }
        }
    }

    // ── Token helpers ────────────────────────────────────────────────────────

    fun buildMainToken(channelId: String): String = "MediaProfile${channelId.padStart(3, '0')}01"
    fun buildSubToken(channelId: String): String = "MediaProfile${channelId.padStart(3, '0')}02"
    fun buildVideoSourceToken(channelId: String): String = "V_SRC_${channelId.padStart(3, '0')}01"

    // ── CRUD response format (C# parser requires exact shape) ───────────────

    private fun crudSuccess(message: String): JsonObject = buildJsonObject {
        put("status", true)
        put("message", message)
        put("error_code", "")
        putJsonObject("data") { put("status", "") }
    }

    private fun crudSuccessWithFrigate(message: String, frigateRestarted: Boolean): JsonObject = buildJsonObject {
        put("status", true)
        put("message", message)
        put("error_code", "")
        putJsonObject("data") {
            put("status", "")
            put("frigate_restarted", frigateRestarted)
        }
    }

    // ── WS-Discovery ─────────────────────────────────────────────────────────

    private fun wsDiscovery(): List<String> {
        val discovered = mutableListOf<String>()
        val probe = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
            xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
            xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
            xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
  <e:Header>
    <w:MessageID>uuid:${java.util.UUID.randomUUID()}</w:MessageID>
    <w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
    <w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
  </e:Header>
  <e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body>
</e:Envelope>"""
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 3_000
                val bytes = probe.toByteArray()
                socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("239.255.255.250"), 3702))
                val buf = ByteArray(4096)
                val end = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < end) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        socket.receive(pkt)
                        discovered.add(pkt.address.hostAddress)
                    } catch (_: java.net.SocketTimeoutException) { break }
                }
            }
        } catch (_: Exception) {}
        return discovered.distinct()
    }
}
