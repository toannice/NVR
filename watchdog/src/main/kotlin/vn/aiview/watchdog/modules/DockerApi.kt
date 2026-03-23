package vn.aiview.watchdog.modules

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight wrapper around Docker Unix socket HTTP API.
 * Communicates via /var/run/docker.sock using a local HTTP gateway
 * (docker CLI is invoked directly since the container has docker-cli installed).
 */
class DockerApi {
    private val log = LoggerFactory.getLogger(javaClass)

    // Gets container state: "running", "exited", "healthy", "unhealthy", etc.
    fun getContainerState(name: String): String {
        return try {
            val format = "{{.State.Status}}"
            val result = exec("docker", "inspect", "--format=$format", name)
            result.trim().also { log.debug("Container $name state: $it") }
        } catch (e: Exception) {
            log.warn("Cannot inspect container $name: ${e.message}")
            "unknown"
        }
    }

    // Restart a container
    fun restartContainer(name: String): Boolean {
        return try {
            exec("docker", "restart", name)
            log.info("docker restart $name => OK")
            true
        } catch (e: Exception) {
            log.error("Failed to restart $name: ${e.message}")
            false
        }
    }

    // Pull a new image
    fun pullImage(image: String): Boolean {
        return try {
            exec("docker", "pull", image)
            true
        } catch (e: Exception) {
            log.error("Failed to pull $image: ${e.message}")
            false
        }
    }

    // Get the current image ID (digest) of a running container
    fun getContainerImageId(name: String): String? {
        return try {
            exec("docker", "inspect", "--format={{.Image}}", name).trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
    }

    // Get the local image ID for a given tag
    fun getLocalImageId(image: String): String? {
        return try {
            exec("docker", "inspect", "--format={{.Id}}", image).trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
    }

    // Get a specific label from an image
    fun getImageLabel(image: String, label: String): String? {
        return try {
            val format = "{{index .Config.Labels \"$label\"}}"
            exec("docker", "inspect", "--format=$format", image).trim().takeIf { it.isNotBlank() && it != "<no value>" }
        } catch (e: Exception) { null }
    }

    // Stop a container
    fun stopContainer(name: String) { exec("docker", "stop", name) }

    // Start a container
    fun startContainer(name: String) { exec("docker", "start", name) }

    // Remove old image
    fun removeImage(imageId: String) {
        try { exec("docker", "rmi", "-f", imageId) } catch (e: Exception) { /* ignore */ }
    }

    // Check remote registry for newer digest (without downloading full image)
    fun getRemoteDigest(image: String): String? {
        return try {
            // Use docker manifest inspect to compare digests
            val result = exec("docker", "manifest", "inspect", image)
            // Extract a simple hash from the output
            result.lines().firstOrNull { it.contains("\"digest\"") }
                ?.substringAfter("\"digest\": \"")?.substringBefore("\"")
        } catch (e: Exception) {
            log.warn("Cannot check remote digest for $image: ${e.message}")
            null
        }
    }

    // List files in recordings path, sorted oldest first, for cleanup
    fun listOldest(path: String): List<java.io.File> {
        return try {
            java.io.File(path).walkTopDown()
                .filter { it.isFile && (it.extension == "mp4" || it.extension == "mkv") }
                .sortedBy { it.lastModified() }
                .toList()
        } catch (e: Exception) { emptyList() }
    }

    private fun exec(vararg cmd: String): String {
        val process = ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) throw RuntimeException("Command failed: ${cmd.joinToString(" ")}\n$output")
        return output
    }
}
