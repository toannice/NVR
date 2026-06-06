package vn.aiview.nvr.services

import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.selectAll
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import vn.aiview.nvr.cloud.CloudReconnectContext
import vn.aiview.nvr.cloud.DcBridgeManager
import vn.aiview.nvr.cloud.Go2RtcClient
import vn.aiview.nvr.cloud.IceServer
import vn.aiview.nvr.cloud.SignalingHttpClient
import vn.aiview.nvr.cloud.SignalingManager
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.models.CloudConfig
import vn.aiview.nvr.utils.AppLogger
import vn.aiview.nvr.utils.CryptoUtils
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL

object CloudService {
    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var iceServersApplied = false

    fun start() {
        serviceScope.launch { runCloudStartup() }
    }

    fun stop() {
        SignalingManager.disconnect()
        DcBridgeManager.stop()
        serviceScope.cancel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Keep iceServersApplied so reconnects skip the Frigate restart
    }

    private suspend fun runCloudStartup() {
        CloudReconnectContext.reconnect = {
            stop()
            CoroutineScope(Dispatchers.IO).launch { delay(500); start() }
        }

        try {
            // Give go2rtc inside Frigate time to bind ports after compose start.
            delay(5_000)
            // Insert DB rows for cameras in config.yml that have no DB entry (added before the app tracked them).
            CameraService.syncYamlCamerasToDb()
            // Backfill missing channel_ids so resolveStreamId and heartbeat have stable names.
            CameraService.backfillChannelIds()
            // Add persistent {channelId}_main alias entries to config.yml so live view survives Frigate restarts.
            CameraService.ensureChannelAliasesInYaml()

            val config = readCloudConfig() ?: run {
                AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "NO_CONFIG", "No cloud config found, skipping connection")
                registerAllStreams()
                return
            }

            if (!config.enabled) {
                AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "DISABLED", "Cloud connection disabled")
                SignalingManager.setDisabled()
                registerAllStreams()
                return
            }

            SignalingManager.deviceSerial = config.serial
            SignalingManager.setConnecting()
            val mqttInfo = SignalingHttpClient.fetchMqttConnectionInfo(config)
            if (mqttInfo == null) {
                SignalingManager.setError("Server unreachable after 5 attempts")
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "HANDSHAKE_FAILED", "Could not reach cloud server")
                registerAllStreams()
                return
            }

            val iceServers = SignalingHttpClient.fetchIceServers(config)

            // On first startup only: write TURN/ICE servers to Frigate's go2rtc config so
            // live view can traverse NAT. Subsequent reconnects skip this to avoid extra Frigate restarts.
            if (!iceServersApplied && iceServers.isNotEmpty()) {
                val restarted = applyIceServersToGo2Rtc(iceServers)
                iceServersApplied = true
                if (restarted) {
                    AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "WAITING_GO2RTC", "Waiting for go2rtc to restart...")
                }
            }

            // Always wait for go2rtc before registering streams — it may still be starting
            // even when no Frigate restart was needed (e.g. fresh compose up).
            waitForGo2Rtc(timeoutMs = 30_000)
            // Pre-register every camera's go2rtc stream so the first LIVE offer always finds it.
            registerAllStreams()

            DcBridgeManager.start()
            SignalingManager.connect(mqttInfo, iceServers)
        } catch (e: Exception) {
            AppLogger.error(AppLogger.Module.SYSTEM, "CLOUD", "STARTUP_ERROR", e.message ?: "", e)
            SignalingManager.setError(e.message ?: "Startup error")
        }
    }

    private fun readCloudConfig(): vn.aiview.nvr.cloud.CloudConfigData? {
        return transaction {
            CloudConfig.select(
                CloudConfig.domain, CloudConfig.port, CloudConfig.username,
                CloudConfig.passwordEncrypted, CloudConfig.serial, CloudConfig.enabled
            ).where { CloudConfig.id eq 1 }.firstOrNull()
        }?.let { row ->
            val encPwd = row[CloudConfig.passwordEncrypted]
            val password = if (encPwd.isNotBlank()) {
                try { CryptoUtils.decrypt(encPwd) } catch (e: Exception) { "" }
            } else ""
            vn.aiview.nvr.cloud.CloudConfigData(
                domain = row[CloudConfig.domain],
                port = row[CloudConfig.port],
                username = row[CloudConfig.username],
                password = password,
                serial = row[CloudConfig.serial],
                enabled = row[CloudConfig.enabled]
            )
        }
    }

    /** Register every camera's `${channel_id}_main` (and `_sub`) stream via Frigate restream paths. */
    private suspend fun registerAllStreams() {
        val rows = transaction { Cameras.selectAll().toList() }
        var ok = 0
        for (row in rows) {
            val chId = row[Cameras.channelId] ?: continue
            val slug = row[Cameras.slug]
            if (slug.isBlank()) continue
            // Use Frigate's restream URL (127.0.0.1:8554) so cloud live view goes through
            // the same path as local live view. Direct RTSP would create a second camera
            // connection and bypass Frigate's restreamer.
            if (Go2RtcClient.registerStreamSync("${chId}_main", "rtsp://127.0.0.1:8554/$slug")) ok++
            val subRtsp = row[Cameras.subRtspUrl]
            if (!subRtsp.isNullOrBlank()) {
                if (Go2RtcClient.registerStreamSync("${chId}_sub", "rtsp://127.0.0.1:8554/${slug}_sub")) ok++
            }
        }
        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "STREAMS_REGISTERED", "registered=$ok total=${rows.size}")
    }

    /**
     * Write TURN/ICE servers fetched from the cloud into Frigate's go2rtc config so that
     * WebRTC live view can traverse NAT via the TURN relay.
     *
     * Returns true if config was changed and Frigate was restarted.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyIceServersToGo2Rtc(iceServers: List<IceServer>): Boolean {
        val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
        val file = File(configPath)
        if (!file.exists()) return false

        val yaml = Yaml()
        val configData: MutableMap<String, Any> = yaml.load(file.readText()) ?: return false
        val go2rtc = (configData.getOrPut("go2rtc") { mutableMapOf<String, Any>() }) as MutableMap<String, Any>
        val webrtc = (go2rtc.getOrPut("webrtc") { mutableMapOf<String, Any>() }) as MutableMap<String, Any>

        val newIceList = iceServers.map { server ->
            val m = mutableMapOf<String, Any>()
            // UDP 3478 is often blocked by firewalls; add TCP as primary alongside UDP
            // so go2rtc can gather a relay candidate even when UDP is unavailable.
            val expandedUrls = server.urls.flatMap { url ->
                if (url.contains("transport=udp", ignoreCase = true))
                    listOf(url.replace("transport=udp", "transport=tcp", ignoreCase = true), url)
                else listOf(url)
            }
            m["urls"] = expandedUrls
            if (!server.username.isNullOrEmpty()) {
                m["username"] = server.username!!
                m["credential"] = server.credential ?: ""
            }
            m as Any
        }

        val hostIp = detectLanIp()
        var changed = false

        @Suppress("UNCHECKED_CAST")
        val currentCandidates = webrtc["candidates"] as? List<*>
        if (hostIp.isNotEmpty() && currentCandidates != null) {
            val hasPlaceholder = currentCandidates.any {
                val s = it?.toString() ?: ""
                s.contains("{FRIGATE_HOST_IP}") || s.startsWith("'{FRIGATE_HOST_IP}")
            }
            if (hasPlaceholder) {
                webrtc["candidates"] = currentCandidates.map { c ->
                    c?.toString()
                        ?.removePrefix("'")?.removeSuffix("'")  // strip SnakeYAML-added quotes
                        ?.replace("{FRIGATE_HOST_IP}", hostIp)
                        ?: c
                }
                changed = true
                AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "GO2RTC_CANDIDATE_FIXED",
                    "Replaced {FRIGATE_HOST_IP} → $hostIp in go2rtc candidates")
            }
        }

        @Suppress("UNCHECKED_CAST")
        val existingIce = webrtc["ice_servers"] as? List<*>
        val existingUrls = existingIce?.flatMap { entry ->
            @Suppress("UNCHECKED_CAST")
            ((entry as? Map<*, *>)?.get("urls") as? List<*>)?.map { it.toString() } ?: emptyList()
        } ?: emptyList()
        val wantedUrls = newIceList.flatMap { entry ->
            @Suppress("UNCHECKED_CAST")
            ((entry as? Map<*, *>)?.get("urls") as? List<*>)?.map { it.toString() } ?: emptyList()
        }
        if (existingIce == null || existingUrls.toSet() != wantedUrls.toSet()) {
            changed = true
        }

        if (!changed) {
            AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "GO2RTC_ICE_UNCHANGED", "go2rtc config already up to date, skipping restart")
            return false
        }

        webrtc["ice_servers"] = newIceList
        val options = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK }
        FileWriter(file).use { Yaml(options).dump(configData, it) }
        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "GO2RTC_ICE_UPDATED",
            "Updated go2rtc config: ${iceServers.size} ICE server(s), host=$hostIp — restarting Frigate")

        return try {
            val conn = URL("${System.getenv("FRIGATE_URL") ?: "http://localhost:5000"}/api/restart")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "FRIGATE_RESTART_FAIL", e.message ?: "")
            false
        }
    }

    /** Detect the host's LAN IP by filtering active non-loopback/non-docker interfaces. */
    private fun detectLanIp(): String {
        // Prefer FRIGATE_HOST_IP env if set (e.g. via docker-compose.yml or .env)
        val envIp = System.getenv("FRIGATE_HOST_IP")?.takeIf { it.isNotBlank() }
        if (envIp != null) return envIp
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()
                .toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .filterNot {
                    val n = it.name.lowercase()
                    n.startsWith("docker") || n.startsWith("br-") || n.startsWith("veth") ||
                        n.startsWith("zt")  // ZeroTier virtual mesh interfaces (10.34.x.x range)
                }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { ip ->
                    ip.startsWith("192.168.") || ip.startsWith("10.") ||
                        ip.matches(Regex("^172\\.(1[6-9]|2\\d|3[01])\\..*"))
                } ?: ""
        } catch (_: Exception) { "" }
    }

    /** Poll until go2rtc's HTTP API responds or timeout is reached. */
    private suspend fun waitForGo2Rtc(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = URL("http://127.0.0.1:1984/api/streams").openConnection() as HttpURLConnection
                conn.connectTimeout = 1_000
                conn.readTimeout = 1_000
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) {
                    AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "GO2RTC_READY", "go2rtc is up after Frigate restart")
                    return
                }
            } catch (_: Exception) {}
            delay(1_000)
        }
        AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "GO2RTC_TIMEOUT", "go2rtc not ready within ${timeoutMs}ms")
    }
}
