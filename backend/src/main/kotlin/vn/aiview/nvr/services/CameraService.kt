package vn.aiview.nvr.services

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import vn.aiview.nvr.cloud.Go2RtcClient
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.routes.OnvifSoapClient
import vn.aiview.nvr.utils.AppLogger
import vn.aiview.nvr.utils.CryptoUtils
import vn.aiview.nvr.utils.HwaccelDetector
import java.io.File
import java.io.FileWriter

data class AppliedCamera(
    val slug: String,
    val channelId: String,
    val rtspUrl: String,
    val mainToken: String,
    val subToken: String,
    val frigateRestarted: Boolean,
)

/**
 * Single source of truth for camera CRUD. Both the local Web UI ([CameraRouting])
 * and the cloud command handlers ([CameraCommandHandler]) call into here so the
 * unified [Cameras] table, Frigate config.yml, go2rtc registration, and Frigate
 * reload happen exactly once per change, serialized by [CameraConfigMutex].
 */
object CameraService {
    private const val FRIGATE_RESTART_URL_ENV = "FRIGATE_URL"
    private const val DEFAULT_FRIGATE_URL = "http://localhost:5000"
    private const val DEFAULT_CONFIG_PATH = "config/config.yml"

    private fun configPath(): String = System.getenv("CONFIG_PATH") ?: DEFAULT_CONFIG_PATH
    private fun frigateBase(): String = System.getenv(FRIGATE_RESTART_URL_ENV) ?: DEFAULT_FRIGATE_URL

    private fun buildSubToken(channelId: String): String = "MediaProfile${channelId.padStart(3, '0')}02"

    /** Công tắc AI tổng đang bật? Camera mới kế thừa giá trị này thay vì bật cứng. */
    private fun globalDetectEnabled(): Boolean = try {
        val file = File(configPath())
        if (!file.exists()) true
        else (Yaml().load<Map<String, Any>>(file.readText())?.get("detect") as? Map<*, *>)
            ?.get("enabled")?.toString()?.toBoolean() ?: true
    } catch (_: Exception) {
        true
    }

    suspend fun applyAndPersistFromOnvif(
        channelId: String,
        host: String,
        port: Int,
        user: String,
        pass: String,
        displayName: String,
        manufacturer: String = "Unknown",
        model: String = "Unknown",
        onvifTimeoutMs: Long = 10_000,
        // Gateway provisioning (4-6 Plan A): when the camera lives behind an edge gateway, ONVIF
        // GetStreamUri returns the camera's field-LAN RTSP host (e.g. 192.168.2.64:554) which the
        // NVR cannot reach. Rewrite the RTSP authority to the gateway's proxy (zerotierIp:rtspPort)
        // while keeping creds + path. gatewayId/localIp/assignedRtspPort are stored as metadata.
        rtspProxyHostPort: String? = null,
        gatewayId: String? = null,
        localIp: String? = null,
        assignedRtspPort: Int? = null,
    ): Result<AppliedCamera> = runCatching {
        val info = withTimeout(onvifTimeoutMs) {
            OnvifSoapClient.getDeviceInfoAndStream(host, port, user, pass)
        }
        val rtspUrl = if (rtspProxyHostPort != null) rewriteRtspHost(info.rtspUrl, rtspProxyHostPort) else info.rtspUrl
        val subRtspUrl = if (rtspProxyHostPort != null) info.subRtspUrl?.let { rewriteRtspHost(it, rtspProxyHostPort) } else info.subRtspUrl
        val mainToken = info.profileToken.ifBlank { "MediaProfile${channelId.padStart(3, '0')}01" }
        val subToken = buildSubToken(channelId)
        val slug = "cam_$channelId"

        configMutexApply(
            slug = slug,
            channelId = channelId,
            displayName = displayName,
            ip = host,
            onvifPort = port,
            username = user,
            password = pass,
            manufacturer = manufacturer,
            model = model,
            mainToken = mainToken,
            subToken = subToken,
            rtspUrl = rtspUrl,
            subRtspUrl = subRtspUrl,
            record = true,
            gatewayId = gatewayId,
            localIp = localIp,
            assignedRtspPort = assignedRtspPort,
        )
    }

    /** Web UI variant — caller already has a fully-formed RTSP URL (no ONVIF probe). */
    suspend fun applyAndPersistFromRtsp(
        slug: String,
        displayName: String,
        rtspUrl: String,
        subRtspUrl: String? = null,
        ip: String,
        onvifPort: Int,
        ptzEnabled: Boolean,
        record: Boolean,
    ): Result<AppliedCamera> = runCatching {
        val channelId = transaction {
            val existing = Cameras.selectAll().where { Cameras.slug eq slug }.firstOrNull()
            existing?.get(Cameras.channelId) ?: assignNextChannelIdLocked()
        }
        val mainToken = "MediaProfile${channelId.padStart(3, '0')}01"
        val subToken = buildSubToken(channelId)

        configMutexApply(
            slug = slug,
            channelId = channelId,
            displayName = displayName,
            ip = ip,
            onvifPort = onvifPort,
            username = null,
            password = null,
            manufacturer = "Unknown",
            model = "Unknown",
            mainToken = mainToken,
            subToken = subToken,
            rtspUrl = rtspUrl,
            subRtspUrl = subRtspUrl,
            record = record,
            ptzEnabled = ptzEnabled,
        )
    }

    /**
     * Attach (or replace) the sub-stream of an EXISTING camera, re-applying through the unified
     * path so DB + config.yml (dual-stream) + go2rtc + Frigate stay consistent. Used by the Web UI
     * edit flow for cameras added before sub-stream probing existed (their 2nd ONVIF profile is
     * still reachable — just was never stored). Pass subRtspUrl=null to drop the sub-stream.
     */
    suspend fun setSubStream(slugOrChannelId: String, subRtspUrl: String?): Result<AppliedCamera> = runCatching {
        val row = transaction {
            Cameras.selectAll().where {
                (Cameras.slug eq slugOrChannelId) or (Cameras.channelId eq slugOrChannelId)
            }.firstOrNull()
        } ?: throw IllegalArgumentException("Camera not found: $slugOrChannelId")

        val channelId = row[Cameras.channelId]?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Camera $slugOrChannelId has no channel_id")
        // Decrypt the stored password so configMutexApply re-encrypts the SAME secret instead of wiping it.
        val plainPass = row[Cameras.passwordEncrypted]?.takeIf { it.isNotBlank() }
            ?.let { runCatching { CryptoUtils.decrypt(it) }.getOrNull() }

        configMutexApply(
            slug = row[Cameras.slug],
            channelId = channelId,
            displayName = row[Cameras.displayName],
            ip = row[Cameras.ip] ?: "",
            onvifPort = row[Cameras.onvifPort],
            username = row[Cameras.username],
            password = plainPass,
            manufacturer = row[Cameras.manufacturer],
            model = row[Cameras.model],
            mainToken = row[Cameras.mainToken].ifBlank { "MediaProfile${channelId.padStart(3, '0')}01" },
            subToken = row[Cameras.subToken].ifBlank { buildSubToken(channelId) },
            rtspUrl = row[Cameras.rtspUrl],
            subRtspUrl = subRtspUrl,
            record = true,
            ptzEnabled = row[Cameras.ptzEnabled],
            gatewayId = row[Cameras.gatewayId],
            localIp = row[Cameras.localIp],
            assignedRtspPort = row[Cameras.assignedRtspPort],
        )
    }

    /**
     * Đổi đường kéo luồng của một camera ("auto" | "rtsp" | "rtsps") rồi ghi lại config.yml và nạp
     * lại Frigate — giống hệt đường đi của [setSubStream], vì thay đổi chỉ có tác dụng khi
     * `go2rtc.streams` được viết lại.
     */
    suspend fun setStreamTransport(slugOrChannelId: String, mode: String): Result<AppliedCamera> = runCatching {
        val normalized = mode.trim().lowercase()
        require(normalized in RtspTransport.VALID_MODES) {
            "stream_transport không hợp lệ: '$mode' (chỉ nhận ${RtspTransport.VALID_MODES.joinToString("/")})"
        }
        val row = transaction {
            Cameras.selectAll().where {
                (Cameras.slug eq slugOrChannelId) or (Cameras.channelId eq slugOrChannelId)
            }.firstOrNull()
        } ?: throw IllegalArgumentException("Camera not found: $slugOrChannelId")

        val slug = row[Cameras.slug]
        val channelId = row[Cameras.channelId]?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Camera $slugOrChannelId has no channel_id")
        val plainPass = row[Cameras.passwordEncrypted]?.takeIf { it.isNotBlank() }
            ?.let { runCatching { CryptoUtils.decrypt(it) }.getOrNull() }

        val previous = row[Cameras.streamTransport]
        transaction {
            Cameras.update({ Cameras.slug eq slug }) { it[Cameras.streamTransport] = normalized }
        }
        AppLogger.info(AppLogger.Module.CAMERA, "RTSP_TRANSPORT", "MODE_CHANGED",
            "slug=$slug from=$previous to=$normalized")

        configMutexApply(
            slug = slug,
            channelId = channelId,
            displayName = row[Cameras.displayName],
            ip = row[Cameras.ip] ?: "",
            onvifPort = row[Cameras.onvifPort],
            username = row[Cameras.username],
            password = plainPass,
            manufacturer = row[Cameras.manufacturer],
            model = row[Cameras.model],
            mainToken = row[Cameras.mainToken].ifBlank { "MediaProfile${channelId.padStart(3, '0')}01" },
            subToken = row[Cameras.subToken].ifBlank { buildSubToken(channelId) },
            rtspUrl = row[Cameras.rtspUrl],
            subRtspUrl = row[Cameras.subRtspUrl],
            record = true,
            ptzEnabled = row[Cameras.ptzEnabled],
            gatewayId = row[Cameras.gatewayId],
            localIp = row[Cameras.localIp],
            assignedRtspPort = row[Cameras.assignedRtspPort],
        )
    }

    suspend fun removeAndPersist(slugOrChannelId: String): Result<Unit> = runCatching {
        val target = transaction {
            Cameras.selectAll().where {
                (Cameras.slug eq slugOrChannelId) or (Cameras.channelId eq slugOrChannelId)
            }.firstOrNull()
        }
        val slug = target?.get(Cameras.slug) ?: slugOrChannelId
        val channelId = target?.get(Cameras.channelId) ?: slugOrChannelId

        CameraConfigMutex.mutex.withLock {
            transaction { Cameras.deleteWhere { Cameras.slug eq slug } }
            removeFromYaml(slug, channelId)
            Go2RtcClient.deleteStream("${channelId}_main")
            Go2RtcClient.deleteStream("${channelId}_sub")
            triggerFrigateRestart()
        }
        AppLogger.info(AppLogger.Module.CAMERA, "CRUD", "CAMERA_REMOVE", "slug=$slug channel_id=$channelId")
    }

    /** Assign channel_id to every Cameras row that is missing one. Caller wraps in a transaction. */
    fun backfillChannelIds() {
        transaction {
            val rows = Cameras.selectAll().toList()
            val used = rows.mapNotNull { it[Cameras.channelId]?.toIntOrNull() }.toMutableSet()
            for (row in rows.filter { it[Cameras.channelId].isNullOrBlank() }) {
                var next = 1
                while (next in used) next++
                used.add(next)
                Cameras.update({ Cameras.slug eq row[Cameras.slug] }) {
                    it[Cameras.channelId] = next.toString()
                }
            }
        }
    }

    /** Returns the next free channelId without persisting. Caller is inside a transaction. */
    private fun assignNextChannelIdLocked(): String {
        val used = Cameras.selectAll().mapNotNull { it[Cameras.channelId]?.toIntOrNull() }.toSet()
        var next = 1
        while (next in used) next++
        return next.toString()
    }

    // ── internals ────────────────────────────────────────────────────────────

    private suspend fun configMutexApply(
        slug: String,
        channelId: String,
        displayName: String,
        ip: String,
        onvifPort: Int,
        username: String?,
        password: String?,
        manufacturer: String,
        model: String,
        mainToken: String,
        subToken: String,
        rtspUrl: String,
        subRtspUrl: String? = null,
        record: Boolean,
        ptzEnabled: Boolean = false,
        gatewayId: String? = null,
        localIp: String? = null,
        assignedRtspPort: Int? = null,
    ): AppliedCamera {
        val encryptedPass = if (password.isNullOrBlank()) null else CryptoUtils.encrypt(password)
        // Resolve effective slug: a row may already exist for this channel_id under a different
        // slug (e.g. Web UI added "ai_view_xxx" first, cloud now sends put_channels for same
        // channel_id). Match on slug OR channel_id and reuse the existing slug to avoid both
        // a UNIQUE(channel_id) violation and a duplicate YAML/go2rtc entry.
        val effectiveSlug = CameraConfigMutex.mutex.withLock {
            val resolved = transaction {
                val existing = Cameras.selectAll().where {
                    (Cameras.slug eq slug) or (Cameras.channelId eq channelId)
                }.firstOrNull()
                val targetSlug = existing?.get(Cameras.slug) ?: slug
                if (existing == null) {
                    Cameras.insert {
                        it[Cameras.slug] = slug
                        it[Cameras.displayName] = displayName
                        it[Cameras.ip] = ip
                        it[Cameras.onvifPort] = onvifPort
                        it[Cameras.manufacturer] = manufacturer
                        it[Cameras.model] = model
                        it[Cameras.ptzEnabled] = ptzEnabled
                        it[Cameras.channelId] = channelId
                        it[Cameras.username] = username
                        it[Cameras.passwordEncrypted] = encryptedPass
                        it[Cameras.mainToken] = mainToken
                        it[Cameras.subToken] = subToken
                        it[Cameras.rtspUrl] = rtspUrl
                        it[Cameras.subRtspUrl] = subRtspUrl
                        it[Cameras.hasPtz] = ptzEnabled
                        if (gatewayId != null) it[Cameras.gatewayId] = gatewayId
                        if (localIp != null) it[Cameras.localIp] = localIp
                        if (assignedRtspPort != null) it[Cameras.assignedRtspPort] = assignedRtspPort
                        // Camera mới được writeYaml cấp sẵn vùng "Toàn cảnh" khi AI tổng đang bật.
                        // Đánh dấu để vùng đầu tiên người dùng tự vẽ được phép thay thế nó.
                        if (globalDetectEnabled()) {
                            it[Cameras.aiIntent] = AiConfigState.INTENT_ON
                            it[Cameras.aiDefaultZone] = true
                        }
                    }
                } else {
                    Cameras.update({ Cameras.slug eq targetSlug }) {
                        it[Cameras.displayName] = displayName
                        it[Cameras.ip] = ip
                        it[Cameras.onvifPort] = onvifPort
                        it[Cameras.manufacturer] = manufacturer
                        it[Cameras.model] = model
                        it[Cameras.channelId] = channelId
                        it[Cameras.username] = username
                        it[Cameras.passwordEncrypted] = encryptedPass
                        it[Cameras.mainToken] = mainToken
                        it[Cameras.subToken] = subToken
                        it[Cameras.rtspUrl] = rtspUrl
                        it[Cameras.subRtspUrl] = subRtspUrl
                        if (gatewayId != null) it[Cameras.gatewayId] = gatewayId
                        if (localIp != null) it[Cameras.localIp] = localIp
                        if (assignedRtspPort != null) it[Cameras.assignedRtspPort] = assignedRtspPort
                    }
                }
                targetSlug
            }
            // DB giữ URL gốc do ONVIF khai; go2rtc mới là nơi nhận URL đã chọn đường (554 hay 322).
            // Tách ra như vậy để còn dò lại được về sau khi camera đổi firmware — xem
            // services/RtspTransport.kt và specs/nvr-rtsps-dual-transport.md.
            val transportMode = transaction {
                Cameras.selectAll().where { Cameras.slug eq resolved }
                    .firstOrNull()?.get(Cameras.streamTransport)
            } ?: RtspTransport.MODE_AUTO
            val mainUrl = RtspTransport.resolve(rtspUrl, transportMode, resolved)
            val subUrl = subRtspUrl?.let { RtspTransport.resolve(it, transportMode, "${resolved}_sub") }
            writeYaml(resolved, channelId, mainUrl, subUrl, record)
            Go2RtcClient.registerStreamSync("${channelId}_main", "$mainUrl#backchannel=0")
            if (subUrl != null) {
                Go2RtcClient.registerStreamSync("${channelId}_sub", "$subUrl#backchannel=0")
            }
            resolved
        }
        val frigateRestarted = triggerFrigateRestart()
        AppLogger.info(
            AppLogger.Module.CAMERA, "CRUD", "CAMERA_APPLY",
            "slug=$effectiveSlug channel_id=$channelId frigate_restarted=$frigateRestarted"
        )
        return AppliedCamera(effectiveSlug, channelId, rtspUrl, mainToken, subToken, frigateRestarted)
    }

    @Suppress("UNCHECKED_CAST")
    private fun writeYaml(slug: String, channelId: String, rtspUrl: String, subRtspUrl: String?, record: Boolean) {
        val file = File(configPath())
        val yaml = Yaml()
        val configData: MutableMap<String, Any> =
            if (file.exists()) yaml.load(file.readText()) ?: mutableMapOf() else mutableMapOf()

        configData.putIfAbsent("mqtt", mutableMapOf("host" to "mosquitto", "port" to 1883))
        configData.putIfAbsent("record", mutableMapOf(
            "enabled" to true,
            "retain" to mapOf("days" to 7, "mode" to "all")
        ))
        configData.putIfAbsent("detect", mapOf("enabled" to true))
        configData.putIfAbsent("version", "0.16-0")
        configData.putIfAbsent("ffmpeg", mutableMapOf("hwaccel_args" to HwaccelDetector.detectHwaccelArgs()))

        val cameras = (configData.getOrPut("cameras") { mutableMapOf<String, Any>() }) as MutableMap<String, Any>

        val ffmpegInputs = if (subRtspUrl != null) {
            // Dual-stream: main for record, sub for detect
            listOf(
                mapOf(
                    "path" to "rtsp://127.0.0.1:8554/$slug",
                    "input_args" to "preset-rtsp-restream",
                    "roles" to listOf("record")
                ),
                mapOf(
                    "path" to "rtsp://127.0.0.1:8554/${slug}_sub",
                    "input_args" to "preset-rtsp-restream",
                    "roles" to listOf("detect")
                )
            )
        } else {
            // Single-stream fallback: main handles both roles
            listOf(
                mapOf(
                    "path" to "rtsp://127.0.0.1:8554/$slug",
                    "input_args" to "preset-rtsp-restream",
                    "roles" to listOf("record", "detect")
                )
            )
        }

        // AI của camera mới phải KẾ THỪA công tắc tổng, không được bật cứng: khách đã tắt AI toàn
        // hệ thống (vì dùng Box AI) mà thêm camera lại tự bật là sai — camera con đè giá trị global.
        val globalDetectOn = (configData["detect"] as? Map<*, *>)
            ?.get("enabled")?.toString()?.toBoolean() ?: true

        @Suppress("UNCHECKED_CAST")
        val existingCam = cameras[slug] as? Map<String, Any>
        val existingZones = existingCam?.get("zones") as? Map<*, *>
        val isNewCamera = existingCam == null

        val camBlock = mutableMapOf<String, Any>(
            "ffmpeg" to mapOf(
                "hwaccel_args" to HwaccelDetector.detectHwaccelArgs(),
                "inputs" to ffmpegInputs
            ),
            "record" to mapOf(
                "enabled" to record,
                "retain" to mapOf("days" to 7, "mode" to "all")
            )
        )

        if (isNewCamera) {
            camBlock["detect"] = mapOf("enabled" to globalDetectOn)
            if (globalDetectOn) {
                // Cấp sẵn vùng "Toàn cảnh" bắt Người + Phương tiện, ghi THẬT vào config chứ không
                // để frontend vẽ ra một vùng ảo chưa từng được lưu (bug cũ: UI báo AI đang chạy
                // trong khi config.yml không có vùng nào).
                camBlock["zones"] = AiConfigState.defaultZoneBlock()
                camBlock["objects"] = mapOf("track" to AiConfigState.DEFAULT_ZONE_OBJECTS)
                camBlock["review"] = mapOf("alerts" to mapOf("required_zones" to listOf(AiConfigState.DEFAULT_ZONE_NAME)))
            }
        } else {
            // Cập nhật camera (đổi RTSP, đổi tên…) KHÔNG được đụng tới cấu hình AI: khối này ghi
            // đè cả camera nên phải chép lại nguyên vẹn, nếu không người dùng mất sạch vùng đã vẽ.
            existingCam["detect"]?.let { camBlock["detect"] = it }
            existingCam["zones"]?.let { camBlock["zones"] = it }
            existingCam["objects"]?.let { camBlock["objects"] = it }
            existingCam["review"]?.let { camBlock["review"] = it }
            if (existingZones == null) camBlock.putIfAbsent("detect", mapOf("enabled" to globalDetectOn))
        }

        cameras[slug] = camBlock

        val go2rtc = (configData.getOrPut("go2rtc") { mutableMapOf<String, Any>() }) as MutableMap<String, Any>
        val streams = (go2rtc.getOrPut("streams") { mutableMapOf<String, Any>() }) as MutableMap<String, Any>
        streams[slug] = listOf("$rtspUrl#tcp")
        // Persistent alias: cloud live view uses {channelId}_main; must survive go2rtc restarts.
        streams["${channelId}_main"] = listOf("rtsp://127.0.0.1:8554/$slug")
        if (subRtspUrl != null) {
            streams["${slug}_sub"] = listOf("$subRtspUrl#tcp")
            streams["${channelId}_sub"] = listOf("rtsp://127.0.0.1:8554/${slug}_sub")
        }
        // Frigate does NOT substitute {FRIGATE_HOST_IP} in go2rtc config — use actual IP.
        val hostIp = System.getenv("FRIGATE_HOST_IP")?.takeIf { it.isNotBlank() } ?: detectLanIp()
        val webrtcCandidate = if (hostIp.isNotEmpty()) "$hostIp:8555" else "stun:8555"
        go2rtc.putIfAbsent("webrtc", mutableMapOf(
            "candidates" to listOf(webrtcCandidate, "stun:8555")
        ))

        val options = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK }
        FileWriter(file).use { Yaml(options).dump(configData, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun removeFromYaml(slug: String, channelId: String?) {
        val file = File(configPath())
        if (!file.exists()) return
        val yaml = Yaml()
        val configData: MutableMap<String, Any> = yaml.load(file.readText()) ?: return
        (configData["cameras"] as? MutableMap<String, Any>)?.remove(slug)
        val go2rtcStreams = (configData["go2rtc"] as? MutableMap<String, Any>)?.get("streams") as? MutableMap<String, Any>
        go2rtcStreams?.remove(slug)
        go2rtcStreams?.remove("${slug}_sub")
        if (channelId != null) {
            go2rtcStreams?.remove("${channelId}_main")
            go2rtcStreams?.remove("${channelId}_sub")
        }
        val options = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK }
        FileWriter(file).use { Yaml(options).dump(configData, it) }
    }

    /**
     * Sync startup: cameras in config.yml with no DB row get inserted so the cloud `get_channels`
     * and the Web UI both see the same list.  Assigns channel_id, mainToken, subToken from YAML data.
     */
    @Suppress("UNCHECKED_CAST")
    fun syncYamlCamerasToDb() {
        val file = File(configPath())
        if (!file.exists()) return
        val yaml = Yaml()
        val configData = yaml.load<Map<String, Any>>(file.readText()) ?: return
        val yamlCameras = configData["cameras"] as? Map<String, Any> ?: return
        val go2rtcStreams = ((configData["go2rtc"] as? Map<String, Any>)?.get("streams")) as? Map<String, Any>

        transaction {
            val existingSlugs = Cameras.selectAll().map { it[Cameras.slug] }.toSet()
            val usedChannelIds = Cameras.selectAll().mapNotNull { it[Cameras.channelId]?.toIntOrNull() }.toMutableSet()

            for (slug in yamlCameras.keys) {
                if (slug in existingSlugs) continue
                // Resolve RTSP URL from go2rtc streams
                val streamList = go2rtcStreams?.get(slug) as? List<*>
                val rawUrl = streamList?.firstOrNull()?.toString() ?: continue
                val rtspUrl = if (rawUrl.contains("#")) rawUrl.substringBefore("#") else rawUrl
                // Bỏ qua luồng restream nội bộ của go2rtc, kể cả khi nó là rtsps://.
                if (rtspUrl.isBlank() || RtspTransport.isLoopback(rtspUrl)) continue

                var nextId = 1
                while (nextId in usedChannelIds) nextId++
                usedChannelIds.add(nextId)
                val chId = nextId.toString()
                val mainToken = "MediaProfile${chId.padStart(3, '0')}01"
                val subToken = "MediaProfile${chId.padStart(3, '0')}02"
                val ip = parseIpFromRtsp(rtspUrl)

                Cameras.insert {
                    it[Cameras.slug] = slug
                    it[Cameras.displayName] = slug
                    it[Cameras.ip] = ip
                    it[Cameras.channelId] = chId
                    it[Cameras.mainToken] = mainToken
                    it[Cameras.subToken] = subToken
                    it[Cameras.rtspUrl] = rtspUrl
                }
                AppLogger.info(AppLogger.Module.CAMERA, "STARTUP", "YAML_CAM_SYNCED",
                    "slug=$slug channel_id=$chId rtsp=${rtspUrl.take(40)}")
            }
        }
    }

    /** Add missing {channelId}_main alias entries to config.yml so live view survives Frigate restarts. */
    @Suppress("UNCHECKED_CAST")
    suspend fun ensureChannelAliasesInYaml() {
        val file = File(configPath())
        if (!file.exists()) return

        val cameraMap = transaction {
            Cameras.selectAll().mapNotNull { row ->
                val chId = row[Cameras.channelId] ?: return@mapNotNull null
                row[Cameras.slug] to chId
            }.toMap()
        }
        if (cameraMap.isEmpty()) return

        CameraConfigMutex.mutex.withLock {
            val yaml = Yaml()
            val configData = yaml.load<MutableMap<String, Any>>(file.readText()) ?: return@withLock
            val go2rtc = (configData.getOrPut("go2rtc") { mutableMapOf<String, Any>() }) as MutableMap<String, Any>
            val streams = (go2rtc.getOrPut("streams") { mutableMapOf<String, Any>() }) as MutableMap<String, Any>

            var changed = false
            for ((slug, chId) in cameraMap) {
                if (!streams.containsKey(slug)) continue
                if (!streams.containsKey("${chId}_main")) {
                    streams["${chId}_main"] = listOf("rtsp://127.0.0.1:8554/$slug")
                    changed = true
                }
                if (streams.containsKey("${slug}_sub") && !streams.containsKey("${chId}_sub")) {
                    streams["${chId}_sub"] = listOf("rtsp://127.0.0.1:8554/${slug}_sub")
                    changed = true
                }
            }

            if (changed) {
                val options = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK }
                FileWriter(file).use { Yaml(options).dump(configData, it) }
                AppLogger.info(AppLogger.Module.CAMERA, "STARTUP", "ALIASES_ADDED",
                    "Added channelId stream aliases for live view")
            }
        }
    }

    /**
     * Dò lại đường kéo luồng của mọi camera khi NVR khởi động và sửa `go2rtc.streams` nếu cổng đã
     * đổi.
     *
     * Không có bước này thì `stream_transport="auto"` chỉ có tác dụng lúc thêm/sửa camera: camera
     * vừa nâng firmware (554 đóng lại, 322 mở ra) sẽ nằm im mất hình cho tới khi có người vào sửa
     * tay — đúng thứ chúng ta đang muốn tránh. Khởi động lại NVR là đủ để nó tự chữa.
     *
     * Chỉ ghi khi có thay đổi thật, và chỉ nạp lại Frigate một lần cho cả lượt.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun reresolveStreamTransports() {
        val file = File(configPath())
        if (!file.exists()) return

        data class Cam(val slug: String, val chId: String?, val mode: String)
        val cams = transaction {
            Cameras.selectAll().map { row ->
                Cam(row[Cameras.slug], row[Cameras.channelId], row[Cameras.streamTransport])
            }
        }
        if (cams.isEmpty()) return

        var restartNeeded = false
        CameraConfigMutex.mutex.withLock {
            val configData = Yaml().load<MutableMap<String, Any>>(file.readText()) ?: return@withLock
            val go2rtc = configData["go2rtc"] as? MutableMap<String, Any> ?: return@withLock
            val streams = go2rtc["streams"] as? MutableMap<String, Any> ?: return@withLock

            for (cam in cams) {
                for (key in listOf(cam.slug, "${cam.slug}_sub")) {
                    val current = (streams[key] as? List<*>)?.firstOrNull()?.toString() ?: continue
                    // Giữ nguyên hậu tố #tcp/#backchannel=0 — nó là tham số của go2rtc, không thuộc URL.
                    val suffix = if (current.contains('#')) "#" + current.substringAfter('#') else ""
                    val bare = current.substringBefore('#')
                    if (RtspTransport.isLoopback(bare)) continue
                    val resolved = RtspTransport.resolve(bare, cam.mode, key)
                    if (resolved == bare) continue
                    streams[key] = listOf(resolved + suffix)
                    restartNeeded = true
                    AppLogger.info(AppLogger.Module.CAMERA, "RTSP_TRANSPORT", "YAML_UPDATED",
                        "stream=$key from=${RtspTransport.redact(bare)} to=${RtspTransport.redact(resolved)}")
                    // Alias {chId}_main/_sub trỏ vào restream nội bộ nên không cần sửa; nhưng bản
                    // đăng ký sống trong go2rtc thì phải cập nhật, nếu không live view cloud vẫn
                    // dùng URL cũ cho tới lần khởi động lại Frigate kế tiếp.
                    if (cam.chId != null) {
                        val alias = if (key == cam.slug) "${cam.chId}_main" else "${cam.chId}_sub"
                        Go2RtcClient.registerStreamSync(alias, "$resolved#backchannel=0")
                    }
                }
            }

            if (restartNeeded) {
                val options = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK }
                FileWriter(file).use { Yaml(options).dump(configData, it) }
            }
        }

        if (restartNeeded) {
            val restarted = triggerFrigateRestart()
            AppLogger.info(AppLogger.Module.CAMERA, "RTSP_TRANSPORT", "STARTUP_HEALED",
                "da doi cong cho it nhat mot camera, frigate_restarted=$restarted")
        }
    }

    private fun detectLanIp(): String = vn.aiview.nvr.utils.NetUtils.detectLanIp()

    private fun parseIpFromRtsp(rtsp: String): String? {
        if (rtsp.isBlank()) return null
        return try {
            val hostPart = rtsp.substringAfter("//").substringBefore("/")
            val hostWithPort = hostPart.substringAfterLast("@")
            hostWithPort.substringBefore(":")
        } catch (_: Exception) { null }
    }

    /**
     * Rewrites the authority (host:port) of an RTSP URL, preserving scheme, embedded creds and path.
     * Used for gateway cameras: rtsp://user:pass@192.168.2.64:554/path → rtsp://user:pass@10.34.24.12:8081/path
     */
    fun rewriteRtspHost(url: String, hostPort: String): String {
        if (url.isBlank()) return url
        // Giữ nguyên scheme của URL vào: camera qua gateway cũng có thể đang chạy rtsps://.
        // Cách cũ hardcode "rtsp://" nên URL rtsps bị trả về nguyên xi, không đổi host → camera
        // gateway TLS trỏ thẳng vào IP LAN hiện trường và không bao giờ kết nối được.
        val scheme = if (url.startsWith("rtsps://", ignoreCase = true)) "rtsps://"
                     else if (url.startsWith("rtsp://", ignoreCase = true)) "rtsp://"
                     else return url
        val rest = url.substring(scheme.length)
        val slashIdx = rest.indexOf('/')
        val authority = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
        val path = if (slashIdx >= 0) rest.substring(slashIdx) else ""
        val atIdx = authority.lastIndexOf('@')
        val creds = if (atIdx >= 0) authority.substring(0, atIdx + 1) else "" // keeps trailing '@'
        return "$scheme$creds$hostPort$path"
    }

    /**
     * Best-effort empirical PTZ detection (4-6 Plan A). Sends one tiny PanTilt ContinuousMove + Stop
     * through the (proxied) ONVIF endpoint and inspects the SOAP response: a clean response ⇒ the
     * camera accepts ONVIF pan/tilt → ptz_mode="full"; a fault (e.g. "speed out of bounds") ⇒ "zoom".
     * NEVER overrides a manual ptz_mode the user already set. Caveat documented in gateway/legacy/4-6-to-do-jobs.md:
     * some hardware-PTZ cameras (e.g. OSSD2MPVF33XPTZAI) reject ONVIF ContinuousMove and get classified
     * zoom-only here — that is a known false-negative; the manual override remains the escape hatch.
     */
    suspend fun autoDetectPtz(slug: String, ip: String, onvifPort: Int, user: String, pass: String) {
        try {
            val existingMode = transaction {
                Cameras.selectAll().where { Cameras.slug eq slug }.firstOrNull()?.get(Cameras.ptzMode)
            }
            if (!existingMode.isNullOrBlank() && existingMode.lowercase() != "auto") {
                AppLogger.info(AppLogger.Module.CAMERA, "PTZ", "AUTO_PTZ_SKIP", "slug=$slug manual=$existingMode")
                return
            }
            val (ptzUrl, profileToken) = OnvifSoapClient.getPtzServiceUrlAndToken(ip, onvifPort, user, pass)
            val panTiltOk = OnvifSoapClient.probePanTiltSupported(ptzUrl, profileToken, user, pass)
            val mode = if (panTiltOk) "full" else "zoom"
            transaction {
                Cameras.update({ Cameras.slug eq slug }) {
                    it[Cameras.ptzMode] = mode
                    it[Cameras.ptzEnabled] = true
                    it[Cameras.hasPtz] = true
                }
            }
            AppLogger.info(AppLogger.Module.CAMERA, "PTZ", "AUTO_PTZ_DETECT", "slug=$slug mode=$mode panTilt=$panTiltOk")
        } catch (e: Exception) {
            // No PTZ service / unreachable → leave ptz_mode null (UI hides d-pad). Non-fatal.
            AppLogger.info(AppLogger.Module.CAMERA, "PTZ", "AUTO_PTZ_NONE", "slug=$slug: ${e.message}")
        }
    }

    private fun triggerFrigateRestart(): Boolean = try {
        // Frigate stops producing frames for ALL cameras while it restarts — tell the online
        // tracker so it holds states instead of announcing every camera as offline then online.
        StreamOnlineTracker.noteFrigateRestart("camera_config_apply")
        val conn = java.net.URL("${frigateBase()}/api/restart").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.responseCode in 200..299
    } catch (e: Exception) {
        AppLogger.warn(AppLogger.Module.CAMERA, "CRUD", "FRIGATE_RESTART_FAIL", e.message ?: "")
        false
    }
}
