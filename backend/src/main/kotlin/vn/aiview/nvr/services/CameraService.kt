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
    ): Result<AppliedCamera> = runCatching {
        val info = withTimeout(onvifTimeoutMs) {
            OnvifSoapClient.getDeviceInfoAndStream(host, port, user, pass)
        }
        val rtspUrl = info.rtspUrl
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
            subRtspUrl = info.subRtspUrl,
            record = true,
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
                    }
                }
                targetSlug
            }
            writeYaml(resolved, channelId, rtspUrl, subRtspUrl, record)
            Go2RtcClient.registerStreamSync("${channelId}_main", "$rtspUrl#backchannel=0")
            if (subRtspUrl != null) {
                Go2RtcClient.registerStreamSync("${channelId}_sub", "$subRtspUrl#backchannel=0")
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

        cameras[slug] = mapOf(
            "ffmpeg" to mapOf(
                "hwaccel_args" to HwaccelDetector.detectHwaccelArgs(),
                "inputs" to ffmpegInputs
            ),
            "detect" to mapOf("enabled" to true),
            "record" to mapOf(
                "enabled" to record,
                "retain" to mapOf("days" to 7, "mode" to "all")
            )
        )

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
                if (rtspUrl.isBlank() || rtspUrl.startsWith("rtsp://127.0.0.1")) continue

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

    private fun detectLanIp(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces()
            .toList().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filterNot { val n = it.name.lowercase(); n.startsWith("docker") || n.startsWith("br-") || n.startsWith("veth") }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .map { it.hostAddress }
            .firstOrNull { ip -> ip.startsWith("192.168.") || ip.startsWith("10.") || ip.matches(Regex("^172\\.(1[6-9]|2\\d|3[01])\\..*")) }
            ?: ""
    } catch (_: Exception) { "" }

    private fun parseIpFromRtsp(rtsp: String): String? {
        if (rtsp.isBlank()) return null
        return try {
            val hostPart = rtsp.substringAfter("//").substringBefore("/")
            val hostWithPort = hostPart.substringAfterLast("@")
            hostWithPort.substringBefore(":")
        } catch (_: Exception) { null }
    }

    private fun triggerFrigateRestart(): Boolean = try {
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
