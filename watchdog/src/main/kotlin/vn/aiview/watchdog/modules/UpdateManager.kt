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
    private val hostname = System.getenv("HOSTNAME") ?: "nvr-node"

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
        val managedContainers = config.containers.filter { it.managed }
        for (container in managedContainers) {
            log.info("Checking for updates: ${container.name} (${container.image})")
            tryUpdate(container.name, container.image, container.health_endpoint)
        }
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

        // Stop old container, start with new image
        docker.stopContainer(containerName)
        docker.startContainer(containerName)

        // Wait for health
        val healthy = waitForHealth(healthEndpoint, config.update.health_wait_seconds)

        if (healthy) {
            saveVersionState(containerName, newImageId)
            
            // Try to get Version/Git SHA from labels
            val revision = docker.getImageLabel(image, "org.opencontainers.image.revision")?.take(7) ?: "unknown"
            
            docker.removeImage(currentImageId)
            db.log(EventType.UPDATE_SUCCESS, "Updated $containerName to $newImageId ($revision)", severity = Severity.INFO)
            alertManager.sendAlert(AlertEvent(AlertType.UPDATE_SUCCESS,
                "🖥️ <b>[$hostname]</b>\n✅ <b>$containerName</b> cập nhật thành công!\n📦 Version: <code>$revision</code>"))
        } else {
            log.error("Health check failed after update — initiating rollback")
            db.log(EventType.UPDATE_FAILED, "Update failed for $containerName — rolling back", severity = Severity.ERROR)
            alertManager.sendAlert(AlertEvent(AlertType.UPDATE_FAILED,
                "🖥️ <b>[$hostname]</b>\n⚠️ <b>$containerName</b> cập nhật THẤT BẠI! Đang rollback..."))
            performRollback(containerName, currentImageId, healthEndpoint)
        }
    }

    private suspend fun performRollback(containerName: String, oldImageId: String, healthEndpoint: String) {
        if (!config.update.enable_rollback) {
            alertManager.sendAlert(AlertEvent(AlertType.UPDATE_FAILED,
                "❌ Update thất bại, rollback bị tắt. Cần can thiệp thủ công cho <b>$containerName</b>!"))
            return
        }

        var rollbackAttempt = 0
        while (rollbackAttempt < config.update.max_rollback_attempts) {
            rollbackAttempt++
            log.info("Rollback attempt $rollbackAttempt for $containerName with image $oldImageId")

            docker.stopContainer(containerName)
            // Restart directly from old image ID (already local — no pull needed)
            try {
                Runtime.getRuntime().exec(arrayOf("docker", "run", "-d", "--name", containerName, oldImageId)).waitFor()
            } catch (e: Exception) {
                docker.startContainer(containerName) // fallback
            }

            val healthy = waitForHealth(healthEndpoint, 60)
            if (healthy) {
                db.log(EventType.ROLLBACK_SUCCESS, "Rollback to $oldImageId succeeded", severity = Severity.INFO)
                alertManager.sendAlert(AlertEvent(AlertType.ROLLBACK_SUCCESS,
                    "↩️ <b>$containerName</b> đã rollback thành công về image cũ!"))
                return
            }
        }

        // All rollback attempts failed
        db.log(EventType.ROLLBACK_FAILED, "All rollback attempts failed for $containerName", severity = Severity.CRITICAL)
        alertManager.sendAlert(AlertEvent(AlertType.CRITICAL,
            "🚨 <b>CRITICAL:</b> $containerName không thể rollback sau ${config.update.max_rollback_attempts} lần — cần can thiệp thủ công!"))
    }

    private suspend fun waitForHealth(endpoint: String, maxSeconds: Long): Boolean {
        if (endpoint.isBlank()) {
            delay(10_000)
            return true // No endpoint configured — assume OK
        }
        val deadline = System.currentTimeMillis() + maxSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000; readTimeout = 3000; requestMethod = "GET"
                }
                if (conn.responseCode in 200..299) return true
            } catch (e: Exception) { /* not yet healthy */ }
            delay(5000)
        }
        return false
    }

    private fun saveVersionState(name: String, imageId: String) {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText("""{"container":"$name","imageId":"$imageId","updatedAt":"${java.time.Instant.now()}"}""")
    }
}
