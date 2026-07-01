package vn.aiview.nvr.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.aiview.nvr.utils.AppLogger
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages ffmpeg subprocesses that serve recordings as real-time MPEG-TS streams.
 * Using `-re` flag ensures frames are delivered at 1x speed, giving go2rtc correct RTP timestamps.
 *
 * go2rtc registers the stream as `ffmpeg:http://<bridge-ip>:<port>/internal/playback-ts/<name>`
 * and its internal ffmpeg reads the MPEG-TS at the pace we send it (= real-time).
 */
object PlaybackStreamer {
    private val processes = ConcurrentHashMap<String, Process>()

    fun start(name: String, frigateHlsUrl: String): Boolean {
        stop(name)
        return try {
            val proc = ProcessBuilder(
                "ffmpeg",
                "-re",                  // read at 1x speed so go2rtc gets correctly-paced RTP timestamps
                "-i", frigateHlsUrl,
                "-c:v", "copy",         // pass-through video, no re-encode
                "-an",                  // drop audio (not needed for NVR playback)
                "-f", "mpegts",
                "pipe:1"
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            processes[name] = proc
            AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK_STREAM", "STARTED",
                "name=$name url=$frigateHlsUrl pid=${proc.pid()}")
            true
        } catch (e: Exception) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK_STREAM", "START_FAIL",
                "name=$name err=${e.message}")
            false
        }
    }

    fun stop(name: String) {
        processes.remove(name)?.let { proc ->
            if (proc.isAlive) {
                proc.destroyForcibly()
                AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK_STREAM", "STOPPED", "name=$name")
            }
        }
    }

    /**
     * Restart an existing stream at a new HLS URL (seek). Same [name] key so go2rtc's ffmpeg
     * reconnects automatically to /internal/playback-ts/{name} and sees the new position.
     */
    fun restart(name: String, newHlsUrl: String): Boolean {
        stop(name)
        Thread.sleep(150) // brief window for pipeTo to notice EOF
        return start(name, newHlsUrl)
    }

    fun isRunning(name: String): Boolean = processes[name]?.isAlive == true

    /**
     * Stream ffmpeg's MPEG-TS output to [sink] until the stream ends or the connection is closed.
     * Blocks until done; call from a coroutine (Dispatchers.IO).
     */
    suspend fun pipeTo(name: String, sink: OutputStream) {
        val proc = processes[name] ?: run {
            AppLogger.warn(AppLogger.Module.SYSTEM, "PLAYBACK_STREAM", "PIPE_NO_PROC", "name=$name")
            return
        }
        try {
            withContext(Dispatchers.IO) {
                proc.inputStream.copyTo(sink)
            }
            AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK_STREAM", "PIPE_DONE", "name=$name")
        } catch (e: Exception) {
            AppLogger.info(AppLogger.Module.SYSTEM, "PLAYBACK_STREAM", "PIPE_CLOSED",
                "name=$name reason=${e.message}")
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
            processes.remove(name)
        }
    }
}
