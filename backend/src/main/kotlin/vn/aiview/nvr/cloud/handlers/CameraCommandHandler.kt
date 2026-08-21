package vn.aiview.nvr.cloud.handlers

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import vn.aiview.nvr.cloud.CommandDispatcher
import vn.aiview.nvr.cloud.DeviceConstants
import vn.aiview.nvr.cloud.SignalingManager
import vn.aiview.nvr.models.AIEvents
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.routes.OnvifSoapClient
import vn.aiview.nvr.services.CameraService
import vn.aiview.nvr.services.StreamOnlineTracker
import vn.aiview.nvr.utils.AppLogger
import java.util.Base64

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
        "get_ai_event_image" -> getAiEventImage(data)
        "get_snapshot" -> getSnapshot(data)
        // Playback funcs arrive on channel_service — delegate to PlaybackCommandHandler
        "post_search_calendar", "get_search_calendar",
        "post_search_findrecordings", "get_search_findrecordings",
        "get_recording_recordingjob",
        "post_select_record", "post_select_record_timestamp",
        "post_control_record" -> PlaybackCommandHandler.handle(func, data)
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
                // Real state, same source as notify_channels_state (see SignalingManager) so the
                // cloud's camera list and device list never disagree.
                val online = StreamOnlineTracker.isOnline(chId)
                addJsonObject {
                    put("channelID", chId)
                    put("status", if (online) "online" else "offline")
                    put("isConnected", online)
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
                // 10s to match CameraService.applyAndPersistFromOnvif (onvifTimeoutMs = 10_000).
                // Was 5s: a camera that answers in 6-8s (common over a gateway proxy or a busy
                // link) verified fine from the Web UI but failed from the cloud dashboard, and the
                // dashboard still listed the camera afterwards — so the device looked broken.
                withTimeout(10_000) { OnvifSoapClient.getDeviceInfoAndStream(host, port, user, pass) }
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
            // Notify cloud immediately so dashboard sees the new camera without waiting for heartbeat
            SignalingManager.triggerChannelStateNotification()
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
            // Notify cloud immediately so dashboard sees the removal without waiting for heartbeat
            SignalingManager.triggerChannelStateNotification()
            crudSuccess("delete.device.success")
        }
    }

    // ── AI Event Image ────────────────────────────────────────────────────────

    private val frigateHttp = HttpClient(CIO) { engine { requestTimeout = 10_000 } }
    private val frigateUrl get() = System.getenv("FRIGATE_URL") ?: "http://localhost:5000"

    /**
     * Fetch an AI event snapshot from Frigate and return it as base64 JSON.
     * Params: event_id (Frigate event ID, also stored as AIEvents.id in SQLite).
     */
    private fun getAiEventImage(data: JsonObject): JsonObject {
        val eventId = data["event_id"]?.jsonPrimitive?.content
            ?: data["uuid"]?.jsonPrimitive?.content    // B01 compat: also accept "uuid"
            ?: return CommandDispatcher.errorResponse("E0007", "Missing event_id")

        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "AI_EVENT_IMAGE_REQ", "eventId=$eventId")

        // Verify event exists in our DB (snapshotPath may legitimately be null)
        val eventRow = try {
            transaction {
                AIEvents.selectAll().where { AIEvents.id eq eventId }.firstOrNull()
            }
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "AI_EVENT_DB_FAIL",
                "eventId=$eventId err=${e.message}")
            null
        }

        if (eventRow == null) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "AI_EVENT_NOT_FOUND",
                "eventId=$eventId not in ai_events DB")
            return CommandDispatcher.errorResponse("E0013", "Event not found: $eventId")
        }
        val snapshotPath = eventRow[AIEvents.snapshotPath]
        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "AI_EVENT_FOUND",
            "eventId=$eventId snapshotPath=$snapshotPath")

        // Fetch snapshot from Frigate
        return runBlocking {
            try {
                val snapUrl = "$frigateUrl/api/events/$eventId/snapshot.jpg"
                AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "AI_EVENT_FETCH",
                    "eventId=$eventId url=$snapUrl")
                val resp = frigateHttp.get(snapUrl)
                if (!resp.status.isSuccess()) {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "AI_EVENT_FRIGATE_FAIL",
                        "eventId=$eventId status=${resp.status.value}")
                    return@runBlocking CommandDispatcher.errorResponse("E0015",
                        "Snapshot not available from Frigate (status ${resp.status.value})")
                }
                val bytes = resp.readBytes()
                val base64 = Base64.getEncoder().encodeToString(bytes)
                AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "AI_EVENT_IMAGE_OK",
                    "eventId=$eventId size=${bytes.size}B base64Len=${base64.length}")
                CommandDispatcher.successResponse(buildJsonObject {
                    put("event_id", eventId)
                    put("image", base64)
                    put("content_type", "image/jpeg")
                    put("size", bytes.size)
                })
            } catch (e: Exception) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "AI_EVENT_FETCH_FAIL",
                    "eventId=$eventId err=${e.message}")
                CommandDispatcher.errorResponse("E0016", "Failed to fetch snapshot: ${e.message}")
            }
        }
    }

    /**
     * ps_service/get_snapshot — the cloud's device list thumbnail.
     *
     * B01 contract: `params.token` = media profile token to grab, `data.url` = cloud endpoint to
     * upload the JPEG to, `data.token` = its access token. The device grabs a still frame and
     * POSTs it as multipart form-data (field "file") to `{url}?access_token={token}`; the cloud
     * then renders that image on the tile. Answering an error here (what we used to do) leaves the
     * tile blank — which is why every camera showed as a grey OFFLINE box with no preview.
     */
    private fun getSnapshot(data: JsonObject): JsonObject {
        val params = data["_params"]?.jsonObject ?: buildJsonObject {}
        val body = data["_data"]?.jsonObject ?: buildJsonObject {}
        val profileToken = params["token"]?.jsonPrimitive?.contentOrNull
            ?: params["sourceToken"]?.jsonPrimitive?.contentOrNull
            ?: body["sourceToken"]?.jsonPrimitive?.contentOrNull
        val uploadUrl = body["url"]?.jsonPrimitive?.contentOrNull
        val accessToken = body["token"]?.jsonPrimitive?.contentOrNull ?: ""
        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "SNAPSHOT_REQ",
            "profileToken=$profileToken uploadUrl=${uploadUrl?.take(120)}")

        if (profileToken.isNullOrBlank()) {
            return CommandDispatcher.errorResponse("E0007", "Missing profile token")
        }
        val row = transaction {
            Cameras.selectAll()
                .where { (Cameras.mainToken eq profileToken) or (Cameras.subToken eq profileToken) }
                .firstOrNull()
        } ?: return CommandDispatcher.errorResponse("E0013", "Camera not found for token $profileToken")
        val slug = row[Cameras.slug]

        return runBlocking {
            try {
                // Frigate keeps the most recent decoded frame per camera — cheap, no extra ffmpeg.
                val snapUrl = "$frigateUrl/api/$slug/latest.jpg?h=480"
                val resp = frigateHttp.get(snapUrl)
                if (!resp.status.isSuccess()) {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "SNAPSHOT_FRIGATE_FAIL",
                        "slug=$slug status=${resp.status.value}")
                    return@runBlocking CommandDispatcher.errorResponse("E0015",
                        "Snapshot not available (Frigate status ${resp.status.value})")
                }
                val bytes = resp.readBytes()

                if (uploadUrl.isNullOrBlank()) {
                    // No upload target: fall back to inline base64 so the cloud can still use it.
                    AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "SNAPSHOT_INLINE",
                        "slug=$slug size=${bytes.size}B (no upload url in request)")
                    return@runBlocking CommandDispatcher.successResponse(buildJsonObject {
                        put("token", profileToken)
                        put("image", Base64.getEncoder().encodeToString(bytes))
                        put("content_type", "image/jpeg")
                        put("size", bytes.size)
                    })
                }

                val target = if (accessToken.isBlank()) uploadUrl
                    else uploadUrl + (if (uploadUrl.contains("?")) "&" else "?") + "access_token=$accessToken"
                val up = frigateHttp.post(target) {
                    setBody(MultiPartFormDataContent(formData {
                        append("file", bytes, Headers.build {
                            append(HttpHeaders.ContentType, "image/jpeg")
                            append(HttpHeaders.ContentDisposition, "filename=\"$slug.jpg\"")
                        })
                    }))
                }
                if (!up.status.isSuccess()) {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "SNAPSHOT_UPLOAD_FAIL",
                        "slug=$slug status=${up.status.value} body=${up.bodyAsText().take(200)}")
                    return@runBlocking CommandDispatcher.errorResponse("E0016",
                        "Snapshot upload failed (status ${up.status.value})")
                }
                AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "SNAPSHOT_UPLOAD_OK",
                    "slug=$slug size=${bytes.size}B")
                CommandDispatcher.successResponse(buildJsonObject {
                    put("token", profileToken)
                    put("size", bytes.size)
                })
            } catch (e: Exception) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "SNAPSHOT_FAIL",
                    "slug=$slug err=${e.message}")
                CommandDispatcher.errorResponse("E0016", "Snapshot failed: ${e.message}")
            }
        }
    }

    private fun getDiscovery(): JsonObject {
        val devices = buildJsonArray {
            try {
                val probed = runBlocking { vn.aiview.nvr.routes.probeOnvifNetwork() }
                AppLogger.info(AppLogger.Module.CLOUD, "DISCOVERY", "SCAN_OK", "count=${probed.size}")
                probed.forEach { p ->
                    addJsonObject {
                        putJsonObject("scopes") {
                            put("hardware", p.hardware); put("location", p.location); put("mac", "")
                            put("manufacture", p.manufacturer); put("name", p.name); put("type", "video_encoder")
                        }
                        putJsonObject("xAdrr") { put("host", p.ip); put("https", if (p.https) 1 else 0); put("port", p.port.toLong()) }
                    }
                }
            } catch (e: Exception) {
                AppLogger.warn(AppLogger.Module.CLOUD, "DISCOVERY", "SCAN_FAIL", "err=${e.message}")
            }
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

}
