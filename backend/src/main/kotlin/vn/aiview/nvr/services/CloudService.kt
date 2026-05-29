package vn.aiview.nvr.services

import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.selectAll
import vn.aiview.nvr.cloud.CloudReconnectContext
import vn.aiview.nvr.cloud.DcBridgeManager
import vn.aiview.nvr.cloud.Go2RtcClient
import vn.aiview.nvr.cloud.SignalingHttpClient
import vn.aiview.nvr.cloud.SignalingManager
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.models.CloudConfig
import vn.aiview.nvr.utils.AppLogger
import vn.aiview.nvr.utils.CryptoUtils

object CloudService {
    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        serviceScope.launch { runCloudStartup() }
    }

    fun stop() {
        SignalingManager.disconnect()
        DcBridgeManager.stop()
        serviceScope.cancel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private suspend fun runCloudStartup() {
        CloudReconnectContext.reconnect = {
            stop()
            CoroutineScope(Dispatchers.IO).launch { delay(500); start() }
        }

        try {
            // Give go2rtc inside Frigate time to bind ports after compose start.
            delay(5_000)
            // Backfill missing channel_ids so resolveStreamId and heartbeat have stable names.
            CameraService.backfillChannelIds()
            // Pre-register every camera's go2rtc stream so the first LIVE offer always finds it.
            registerAllStreams()

            val config = readCloudConfig() ?: run {
                AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "NO_CONFIG", "No cloud config found, skipping connection")
                return
            }

            if (!config.enabled) {
                AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "DISABLED", "Cloud connection disabled")
                SignalingManager.setDisabled()
                return
            }

            SignalingManager.deviceSerial = config.serial
            SignalingManager.setConnecting()
            val mqttInfo = SignalingHttpClient.fetchMqttConnectionInfo(config)
            if (mqttInfo == null) {
                SignalingManager.setError("Server unreachable after 5 attempts")
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "HANDSHAKE_FAILED", "Could not reach cloud server")
                return
            }

            val iceServers = SignalingHttpClient.fetchIceServers(config)

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

    /** Register every camera's `${channel_id}_main` stream so cloud LIVE offers always resolve. */
    private suspend fun registerAllStreams() {
        val rows = transaction { Cameras.selectAll().toList() }
        var ok = 0
        for (row in rows) {
            val chId = row[Cameras.channelId] ?: continue
            val rtsp = row[Cameras.rtspUrl]
            if (rtsp.isBlank()) continue
            if (Go2RtcClient.registerStreamSync("${chId}_main", "$rtsp#backchannel=0")) ok++
        }
        AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "STREAMS_REGISTERED", "registered=$ok total=${rows.size}")
    }
}
