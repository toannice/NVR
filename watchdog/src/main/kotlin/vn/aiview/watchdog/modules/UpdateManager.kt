package vn.aiview.watchdog.modules

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import vn.aiview.watchdog.model.WatchdogConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(
    private val config: WatchdogConfig,
    private val alertManager: AlertManager,
    private val db: EventLogger
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val docker = DockerApi()
    private val stateFile = File("/data/version_state.json")
    private val hostname = System.getenv("HOSTNAME") ?: "nvr-edge-node"

    suspend fun run() {
        val intervalMs = config.update.check_interval_minutes * 60_000L
        while (true) {
            try {
                checkForUpdates()
            } catch (e: Exception) {
                log.error("UpdateManager error: ${e.message}")
            }
            delay(intervalMs)
        }
    }

    private suspend fun checkForUpdates() {
        if (!isInOtaWindow()) {
            log.info("Outside OTA window (ota_hour=${config.update.ota_hour}, tz=${config.schedule.timezone}) — skipping this tick")
            return
        }
        val managedContainers = config.containers.filter { it.managed }
        for (container in managedContainers) {
            log.info("Checking for updates: ${container.name} (${container.image})")
            tryUpdate(container.name, container.image, container.health_endpoint)
        }
    }

    // config.update.ota_hour < 0 → no gate, always allowed (legacy behavior). Otherwise only
    // pull+apply during that hour of the day, in schedule.timezone, so OTA doesn't disrupt daytime use.
    private fun isInOtaWindow(): Boolean {
        val hour = config.update.ota_hour
        if (hour < 0) return true
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of(config.schedule.timezone))
        return now.hour == hour
    }

    private suspend fun tryUpdate(containerName: String, image: String, healthEndpoint: String) {
        val currentImageId = docker.getContainerImageId(containerName) ?: run {
            log.warn("Cannot get current image for $containerName — skipping update check")
            return
        }

        // Pull new image
        log.info("Pulling latest image: $image")
        val pulled = docker.pullImage(image)
        if (!pulled) {
            log.warn("Pull failed for $image — skipping")
            return
        }

        val newImageId = docker.getLocalImageId(image) ?: return
        if (newImageId == currentImageId) {
            log.info("$containerName is already up-to-date ($newImageId)")
            return
        }

        log.info("New image detected for $containerName. Upgrading $currentImageId → $newImageId")
        db.log(EventType.UPDATE_STARTED, "Starting update: $containerName → $newImageId")

        val projectDir = System.getenv("PROJECT_DIR") ?: "/home/toan/my-nvr"

        // Execute docker compose up -d to properly recreate the container with new image
        try {
            docker.exec("docker", "compose", "-f", "$projectDir/docker-compose.yml", "up", "-d", containerName)
        } catch (e: Exception) {
            log.error("Failed to run docker compose up for $containerName: ${e.message}")
        }

        // Wait for health
        val result = waitForHealth(healthEndpoint, config.update.health_wait_seconds)

        if (result.healthy) {
            saveVersionState(containerName, newImageId)

            // Try to get Version/Git SHA from labels
            val revision = docker.getImageLabel(image, "org.opencontainers.image.revision")?.take(7) ?: "unknown"

            docker.removeImage(currentImageId)
            db.log(EventType.UPDATE_SUCCESS, "Updated $containerName to $newImageId ($revision)", severity = Severity.INFO)
            alertManager.sendAlert(AlertEvent(AlertType.UPDATE_SUCCESS,
                "🖥️ <b>[$hostname]</b>\n✅ <b>$containerName</b> cập nhật thành công!\n📦 Version: <code>$revision</code>"))
        } else {
            log.error("Health check failed after update (${result.reason}) — initiating rollback")
            db.log(EventType.UPDATE_FAILED, "Update failed for $containerName — rolling back (${result.reason})", severity = Severity.ERROR)
            alertManager.sendAlert(AlertEvent(AlertType.UPDATE_FAILED,
                "🖥️ <b>[$hostname]</b>\n⚠️ <b>$containerName</b> cập nhật THẤT BẠI! Đang rollback...\n🔍 Lý do: <code>${result.reason}</code>"))
            performRollback(containerName, currentImageId, healthEndpoint)
        }
    }

    private suspend fun performRollback(containerName: String, oldImageId: String, healthEndpoint: String) {
        if (!config.update.enable_rollback) {
            alertManager.sendAlert(AlertEvent(AlertType.UPDATE_FAILED,
                "🖥️ <b>[$hostname]</b>\n❌ Update thất bại, rollback bị tắt. Cần can thiệp thủ công cho <b>$containerName</b>!"))
            return
        }

        var lastReason = "unknown"
        var rollbackAttempt = 0
        while (rollbackAttempt < config.update.max_rollback_attempts) {
            rollbackAttempt++
            log.info("Rollback attempt $rollbackAttempt for $containerName with image $oldImageId")

            val projectDir = System.getenv("PROJECT_DIR") ?: "/home/toan/my-nvr"
            try {
                // Dừng và xoá lỗi
                docker.exec("docker", "rm", "-f", containerName)

                // Rollback an toàn: Tag ID cũ đè lên tag hiện tại (đã resolve từ WRAPPER_TAG) để compose up
                // dùng lại bản cũ. KHÔNG hardcode tag — fallback theo đúng WRAPPER_TAG (mặc định latest).
                val resolvedTag = System.getenv("WRAPPER_TAG") ?: "latest"
                val targetImage = config.containers.firstOrNull { it.name == containerName }?.image
                    ?: "ghcr.io/toannice/nvr:$resolvedTag"
                docker.exec("docker", "tag", oldImageId, targetImage)

                // Chạy lại với cấu hình chuẩn
                docker.exec("docker", "compose", "-f", "$projectDir/docker-compose.yml", "up", "-d", containerName)
            } catch (e: Exception) {
                log.error("Rollback execution failed: ${e.message}")
                docker.startContainer(containerName) // fallback
            }

            val result = waitForHealth(healthEndpoint, 60)
            lastReason = result.reason
            if (result.healthy) {
                db.log(EventType.ROLLBACK_SUCCESS, "Rollback to $oldImageId succeeded", severity = Severity.INFO)
                alertManager.sendAlert(AlertEvent(AlertType.ROLLBACK_SUCCESS,
                    "🖥️ <b>[$hostname]</b>\n↩️ <b>$containerName</b> đã rollback thành công về image cũ!"))
                return
            }
        }

        // All rollback attempts failed
        db.log(EventType.ROLLBACK_FAILED, "All rollback attempts failed for $containerName ($lastReason)", severity = Severity.CRITICAL)
        alertManager.sendAlert(AlertEvent(AlertType.CRITICAL,
            "🖥️ <b>[$hostname]</b>\n🚨 <b>CRITICAL:</b> <b>$containerName</b> không thể rollback sau ${config.update.max_rollback_attempts} lần — cần can thiệp thủ công!\n🔍 Lý do lần cuối: <code>$lastReason</code>"))
    }

    private data class HealthResult(val healthy: Boolean, val reason: String)

    private suspend fun waitForHealth(endpoint: String, maxSeconds: Long): HealthResult {
        if (endpoint.isBlank()) {
            delay(10_000)
            return HealthResult(true, "no health_endpoint configured") // No endpoint — assume OK
        }
        val deadline = System.currentTimeMillis() + maxSeconds * 1000
        var lastError = "no response received"
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000; readTimeout = 3000; requestMethod = "GET"
                }
                val code = conn.responseCode
                if (code in 200..299) return HealthResult(true, "ok ($code)")
                lastError = "HTTP $code from $endpoint"
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message} ($endpoint)"
            }
            delay(5000)
        }
        return HealthResult(false, "timeout after ${maxSeconds}s — last error: $lastError")
    }

    private fun saveVersionState(name: String, imageId: String) {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText("""{"container":"$name","imageId":"$imageId","updatedAt":"${java.time.Instant.now()}"}""")
    }
}
