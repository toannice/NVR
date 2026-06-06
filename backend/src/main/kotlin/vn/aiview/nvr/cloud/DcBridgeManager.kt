package vn.aiview.nvr.cloud

import vn.aiview.nvr.utils.AppLogger
import java.io.File

object DcBridgeManager {
    private var process: Process? = null

    fun start() {
        val path = resolveBinaryPath()
        if (path == null) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "DC_BRIDGE_MISSING",
                "dc-bridge binary not found — DATA sessions will be unavailable. " +
                "Set DC_BRIDGE_PATH or place binary at ~/.aiview/dc-bridge")
            return
        }
        if (!File(path).canExecute()) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "DC_BRIDGE_NOT_EXEC",
                "dc-bridge at $path is not executable")
            return
        }
        try {
            process = ProcessBuilder(path)
                .inheritIO()
                .start()
            AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "DC_BRIDGE_STARTED", "dc-bridge started from $path")
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "DC_BRIDGE_START_FAIL", e.message ?: "")
        }
    }

    fun stop() {
        process?.destroy()
        process = null
    }

    private fun resolveBinaryPath(): String? {
        val envPath = System.getenv("DC_BRIDGE_PATH")
        if (!envPath.isNullOrBlank() && File(envPath).exists()) return envPath

        // Check well-known paths in priority order
        val candidates = listOf(
            "/app/data/dc-bridge",                                        // Docker volume mount (./data)
            "${System.getProperty("user.home")}/.aiview/dc-bridge",       // Host home dir
            "/usr/local/bin/dc-bridge"
        )
        return candidates.firstOrNull { File(it).exists() }
    }
}
