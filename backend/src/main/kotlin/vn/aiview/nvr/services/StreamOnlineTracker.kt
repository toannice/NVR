package vn.aiview.nvr.services

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.utils.AppLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks per-camera `online` status, used by the AiView Box integration AND by the cloud
 * (`get_channels` + `notify_channels_state` — see SignalingManager).
 *
 * `online` = Frigate reports camera_fps > 0 for the camera's slug (frames actually arriving
 * from the physical camera). Previously used go2rtc producers, but go2rtc always lists a
 * producer for every registered stream (even when the upstream camera is offline) because
 * streams point to Frigate's internal RTSP which accepts connections regardless.
 *
 * ## Nguyên tắc: chỉ công bố trạng thái khi CHẮC, và không đổi liên tục
 * Mỗi lần thêm/xoá camera hoặc áp cấu hình là Frigate restart, và trong lúc đó `camera_fps` = 0
 * cho MỌI camera (hoặc /api/stats không trả lời). Báo nguyên trạng đó lên cloud thì thành một
 * chùm "camera X offline / online" trong khi sự kiện thật chỉ là thiết bị đi xuống rồi lên.
 *
 * Bốn cái van, theo thứ tự:
 *  1. Frigate không trả lời (hoặc trả lời mà không có camera nào) → **đóng băng** trạng thái cũ.
 *  2. Frigate mới lên lại (`service.uptime` < [TRUST_UPTIME_SEC]) → được đọc fps>0 để báo online,
 *     nhưng KHÔNG được dùng fps=0 để báo offline. Đây là thay thế cho mốc 90s cứng trước đây:
 *     restart chậm bao lâu cũng đúng, vì mốc tin cậy đo bằng uptime thật của Frigate.
 *  3. Slug chưa có trong /api/stats (Frigate đang nạp lại config sau khi thêm camera) → chưa kết luận.
 *  4. Chống nhấp nháy: về offline cần [OFFLINE_AFTER_MS] không có frame; quay lại online sau khi
 *     đã báo offline cần [ONLINE_STABLE_MS] có frame liên tục; và mỗi kênh chỉ đổi trạng thái
 *     công bố tối đa một lần mỗi [MIN_DWELL_MS] (đường truyền chập chờn qua gateway/Nebula rất dễ
 *     sinh chuỗi off/on nếu không có van này). Thay đổi không bao giờ bị mất, chỉ bị hoãn.
 *
 * Trạng thái "chưa biết" (`stateOf` = null) là có thật và phải được tôn trọng: bên gửi notify
 * BỎ QUA kênh chưa biết thay vì đoán "online" — đoán sai một nhịp là cloud nhận một thông báo giả.
 */
object StreamOnlineTracker {
    private const val POLL_MS = 2_000L

    /** Không có frame quá lâu → offline. */
    private const val OFFLINE_AFTER_MS = 30_000L

    /** Đã báo offline thì phải có frame liên tục bấy nhiêu mới được báo online lại. */
    private const val ONLINE_STABLE_MS = 10_000L

    /** Frigate phải chạy liên tục bấy nhiêu giây thì fps=0 mới được coi là "camera chết". */
    private const val TRUST_UPTIME_SEC = 30L

    /** Chặn thêm sau một lệnh restart chủ ý (uptime cũ có thể còn đọc được vài giây đầu). */
    private const val RESTART_HOLD_MS = 30_000L

    /** Mỗi kênh chỉ đổi trạng thái công bố tối đa 1 lần trong khoảng này. */
    private const val MIN_DWELL_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val http = HttpClient(CIO) { engine { requestTimeout = 5_000 } }
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private val frigateUrl: String
        get() = System.getenv("FRIGATE_URL") ?: "http://127.0.0.1:5000"

    /** channelId -> epoch ms lần cuối Frigate báo camera_fps > 0. */
    private val lastFrameAt = ConcurrentHashMap<String, Long>()

    /** channelId -> epoch ms bắt đầu chuỗi fps>0 hiện tại (xoá khi fps về 0). */
    private val frameStreakSince = ConcurrentHashMap<String, Long>()

    /** channelId -> trạng thái ĐÃ công bố (nguồn duy nhất cho isOnline/notify). */
    private val published = ConcurrentHashMap<String, Boolean>()

    /** channelId -> epoch ms lần công bố gần nhất (cho MIN_DWELL_MS). */
    private val publishedAt = ConcurrentHashMap<String, Long>()

    /** Epoch ms tới lúc đó không được kết luận offline (vừa restart Frigate chủ ý). */
    @Volatile private var holdUntilMs = 0L

    /** Log "Frigate không trả lời" một lần cho mỗi lần mất, không spam mỗi 2 giây. */
    @Volatile private var statsDownSince = 0L

    fun start() {
        if (job != null) return
        holdUntilMs = System.currentTimeMillis() + RESTART_HOLD_MS
        AppLogger.info(AppLogger.Module.SYSTEM, "INTEGRATION", "ONLINE_TRACKER_START",
            "poll=${POLL_MS}ms offlineAfter=${OFFLINE_AFTER_MS}ms onlineStable=${ONLINE_STABLE_MS}ms " +
                "trustUptime=${TRUST_UPTIME_SEC}s dwell=${MIN_DWELL_MS}ms source=frigate_stats")
        job = scope.launch {
            while (isActive) {
                runCatching { pollOnce() }.onFailure {
                    AppLogger.warn(AppLogger.Module.SYSTEM, "INTEGRATION", "ONLINE_TRACKER_ERR", it.message ?: "")
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private suspend fun pollOnce() {
        val channelToSlug: Map<String, String> = transaction {
            Cameras.selectAll().mapNotNull { row ->
                val ch = row[Cameras.channelId] ?: return@mapNotNull null
                ch to row[Cameras.slug]
            }.toMap()
        }

        val now = System.currentTimeMillis()
        val stats = frigateStats()

        // Van 1: Frigate không trả lời (hoặc trả lời mà không thấy camera nào dù DB có) = sự kiện
        // cấp thiết bị, không phải cấp camera. Đóng băng trạng thái, không kết luận gì.
        if (stats == null || (stats.slugFps.isEmpty() && channelToSlug.isNotEmpty())) {
            if (statsDownSince == 0L) {
                statsDownSince = now
                AppLogger.info(AppLogger.Module.SYSTEM, "INTEGRATION", "ONLINE_TRACKER_HOLD",
                    "Frigate stats không dùng được — giữ nguyên trạng thái camera đang công bố")
            }
            return
        }
        if (statsDownSince != 0L) {
            AppLogger.info(AppLogger.Module.SYSTEM, "INTEGRATION", "ONLINE_TRACKER_RESUME",
                "Frigate stats trở lại sau ${(now - statsDownSince) / 1000}s (uptime=${stats.uptimeSec}s)")
            statsDownSince = 0L
        }

        // Van 2: fps=0 chỉ đáng tin khi Frigate đã chạy ổn định một lúc và không vừa bị ta restart.
        val trustZeroFps = stats.uptimeSec >= TRUST_UPTIME_SEC && now >= holdUntilMs

        // Quên các kênh không còn tồn tại (camera đã xoá không được đọng lại).
        (lastFrameAt.keys + published.keys + frameStreakSince.keys + publishedAt.keys)
            .filter { it !in channelToSlug }
            .forEach {
                lastFrameAt.remove(it); published.remove(it)
                frameStreakSince.remove(it); publishedAt.remove(it)
            }

        for ((channelId, slug) in channelToSlug) {
            val fps = slugFpsOf(stats, slug)
            // Van 3: Frigate khoẻ nhưng chưa biết slug này → config reload còn đang bay
            // (camera vừa thêm). Không phải lỗi của camera.
            if (fps == null) continue

            if (fps > 0.0) {
                lastFrameAt[channelId] = now
                frameStreakSince.putIfAbsent(channelId, now)
            } else {
                frameStreakSince.remove(channelId)
            }

            val prev = published[channelId]
            val streakMs = frameStreakSince[channelId]?.let { now - it } ?: 0L
            val silentMs = now - (lastFrameAt[channelId] ?: 0L)

            val want: Boolean? = when {
                // Đang có frame: online. Nếu trước đó đã báo offline thì phải ổn định đủ lâu.
                fps > 0.0 && (prev != false || streakMs >= ONLINE_STABLE_MS) -> true
                // Không có frame đủ lâu: offline — nhưng chỉ khi được phép kết luận.
                fps <= 0.0 && silentMs > OFFLINE_AFTER_MS && trustZeroFps -> false
                else -> null
            }
            if (want == null || want == prev) continue

            // Van 4: chống nhấp nháy. Hoãn, không bỏ — vòng poll sau sẽ xét lại.
            if (prev != null && now - (publishedAt[channelId] ?: 0L) < MIN_DWELL_MS) continue

            published[channelId] = want
            publishedAt[channelId] = now
            AppLogger.info(AppLogger.Module.SYSTEM, "INTEGRATION", "ONLINE_TRACKER_CHANGE",
                "ch$channelId ($slug) ${if (prev == null) "unknown" else if (prev) "online" else "offline"}" +
                    " → ${if (want) "online" else "offline"} fps=$fps silent=${silentMs / 1000}s")
        }
    }

    /**
     * Called right before Frigate is restarted on purpose (camera add/delete, config apply) so the
     * fps blackout that follows is never mistaken for cameras dropping off. Chỉ là van phụ —
     * van chính là `service.uptime` của Frigate, nên restart lâu bao nhiêu cũng an toàn.
     */
    fun noteFrigateRestart(reason: String) {
        holdUntilMs = System.currentTimeMillis() + RESTART_HOLD_MS
        AppLogger.info(AppLogger.Module.SYSTEM, "INTEGRATION", "ONLINE_TRACKER_RESTART_HOLD",
            "reason=$reason hold=${RESTART_HOLD_MS}ms (+ chờ uptime≥${TRUST_UPTIME_SEC}s) — tạm không báo camera offline")
    }

    /** True while camera states are frozen because the DEVICE (not a camera) is in transition. */
    fun isHolding(): Boolean =
        System.currentTimeMillis() < holdUntilMs || statsDownSince != 0L

    /**
     * Trạng thái đã công bố, hoặc null khi CHƯA kết luận được (mới khởi động, Frigate chưa đủ
     * uptime, slug chưa xuất hiện trong stats). Bên gửi notify phải bỏ qua kênh null thay vì đoán.
     */
    fun stateOf(channelId: String): Boolean? = published[channelId]

    /**
     * Debounced online state for one camera. Chưa biết → coi là online: dùng cho các API trả lời
     * tại chỗ (`get_channels`, Box integration) mà buộc phải có một giá trị.
     */
    fun isOnline(channelId: String): Boolean = published[channelId] ?: true

    private data class FrigateStats(val uptimeSec: Long, val slugFps: Map<String, Double>)

    private fun slugFpsOf(stats: FrigateStats, slug: String): Double? = stats.slugFps[slug]

    /** GET Frigate /api/stats → uptime + slug→camera_fps, hoặc null khi không đọc được. */
    private suspend fun frigateStats(): FrigateStats? {
        return try {
            val resp = http.get("$frigateUrl/api/stats")
            if (resp.status.value !in 200..299) return null
            val root = lenientJson.parseToJsonElement(resp.bodyAsText()).jsonObject
            val cameras = root["cameras"]?.jsonObject ?: return null
            val uptime = root["service"]?.jsonObject?.get("uptime")?.jsonPrimitive?.longOrNull ?: 0L
            FrigateStats(
                uptimeSec = uptime,
                slugFps = buildMap {
                    for ((slug, info) in cameras) {
                        put(slug, info.jsonObject["camera_fps"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
                    }
                }
            )
        } catch (_: Exception) {
            null
        }
    }
}
