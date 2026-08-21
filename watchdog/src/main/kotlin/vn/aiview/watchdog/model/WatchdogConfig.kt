package vn.aiview.watchdog.model

import org.yaml.snakeyaml.Yaml
import java.io.File

data class ContainerConfig(
    val name: String = "",
    val image: String = "",
    val health_endpoint: String = "",
    val managed: Boolean = false
)

data class ThresholdConfig(
    val cpu_percent: Int = 95,
    val ram_percent: Int = 90,
    val disk_percent: Int = 95,
    val temp_celsius: Int = 85,
    val consecutive_failures: Int = 2
)

data class ScheduleConfig(
    val report_hour: Int = 9,
    val report_minute: Int = 0,
    val timezone: String = "Asia/Ho_Chi_Minh"
)

data class UpdateConfig(
    val check_interval_minutes: Long = 60,
    val enable_rollback: Boolean = true,
    val max_rollback_attempts: Int = 2,
    val health_wait_seconds: Long = 120,
    // Hour (0-23, schedule.timezone) at which OTA is actually allowed to pull+apply new images.
    // -1 = no gate (apply as soon as a new image is seen, at every check_interval_minutes tick —
    // the old behavior, kept as the default so existing installs without this key are unaffected).
    val ota_hour: Int = -1
)

/**
 * Cấu hình OTA (khối `ota:` trong watchdog.yml).
 *
 * Thay cho [UpdateConfig] cũ — cái đó dành cho cơ chế registry pull đã bị bỏ (phải để PAT
 * plaintext trên máy khách). [UpdateConfig] còn lại để không làm hỏng watchdog.yml của máy
 * đang chạy bản cũ, nhưng code mới dùng khối này.
 */
data class OtaConfig(
    /** false = chỉ nhận gói upload tay qua Web UI, không tự hỏi server. */
    val enabled: Boolean = false,

    /** Gốc URL phát hành. URL manifest = <base>/<DEVICE_MODEL>/OTA/manifest.json */
    val base_url: String = "https://aiview.bkav.com/download",

    /** Ghi đè hẳn URL manifest nếu cần (test cục bộ, mirror riêng). Rỗng = tự dựng từ base_url. */
    val manifest_url: String = "",

    /** Giờ (0-23, theo schedule.timezone) được phép tải + áp. */
    val hour: Int = 2,

    /**
     * Rải thời điểm gọi trong khoảng này (phút).
     *
     * Cả fleet gọi cùng lúc 02:00:00 sẽ tự DDoS domain phát hành. Lệch theo hostname (ổn định
     * qua restart) nên mỗi máy có một phút cố định riêng trong cửa sổ.
     */
    val jitter_minutes: Int = 40,

    /** true = tải xong verify xong thì áp luôn. false = chờ admin bấm trong Web UI. */
    val auto_apply: Boolean = true,

    /** 0 = không giới hạn. Máy đang ghi hình, đừng ăn hết đường truyền. */
    val max_download_mbps: Int = 20,

    /** Số lần thử lại khi tải đứt (có nối tiếp bằng HTTP Range). */
    val download_retries: Int = 5,

    /** Cho phép pull image công khai theo digest (đường nâng Frigate). Luôn đi anonymous. */
    val allow_registry_pull: Boolean = true,

    /** Nhịp kiểm request.json từ Web UI và kiểm cửa sổ giờ. */
    val poll_seconds: Long = 20,
)

data class SelfHealingConfig(
    val max_restarts_per_hour: Int = 3,
    val disk_cleanup_trigger_percent: Int = 88,
    val disk_cleanup_target_percent: Int = 83
)

data class TelegramConfig(
    val bot_token: String = "",
    val chat_id: String = ""
)

data class LogConfig(
    val level: String = "INFO",
    val keep_days: Int = 90
)

data class WatchdogConfig(
    val containers: List<ContainerConfig> = emptyList(),
    val thresholds: ThresholdConfig = ThresholdConfig(),
    val schedule: ScheduleConfig = ScheduleConfig(),
    val update: UpdateConfig = UpdateConfig(),
    val ota: OtaConfig = OtaConfig(),
    val self_healing: SelfHealingConfig = SelfHealingConfig(),
    val telegram: TelegramConfig = TelegramConfig(),
    val log: LogConfig = LogConfig()
)

fun loadConfig(path: String): WatchdogConfig {
    val yaml = Yaml()
    // WRAPPER_IMAGE names the LOCAL nvr-wrapper image (built from source or loaded from the
    // offline bundle). There is no registry any more, so there is no tag/channel to resolve —
    // only the local image name the compose file runs. WRAPPER_TAG is still substituted for
    // machines whose watchdog.yml predates this change, so an old config file can't leave a
    // literal "${WRAPPER_TAG}" in the image name.
    val rawText = File(path).readText()
        .replace("\${TELEGRAM_BOT_TOKEN}", System.getenv("TELEGRAM_BOT_TOKEN") ?: "")
        .replace("\${TELEGRAM_CHAT_ID}", System.getenv("TELEGRAM_CHAT_ID") ?: "")
        .replace("\${WRAPPER_IMAGE}", System.getenv("WRAPPER_IMAGE") ?: "nvr-wrapper:latest")
        .replace("\${WRAPPER_TAG}", System.getenv("WRAPPER_TAG") ?: "latest")
        // Ghi đè URL manifest không cần sửa file config: dùng cho test cục bộ và cho mirror
        // riêng. Rỗng thì OtaFetcher tự dựng URL từ base_url + DEVICE_MODEL.
        .replace("\${OTA_MANIFEST_URL}", System.getenv("OTA_MANIFEST_URL") ?: "")

    @Suppress("UNCHECKED_CAST")
    val raw = yaml.load<Map<String, Any>>(rawText)

    fun mapContainers(list: List<Map<String, Any>>?) = list?.map {
        ContainerConfig(
            name = it["name"] as? String ?: "",
            image = it["image"] as? String ?: "",
            health_endpoint = it["health_endpoint"] as? String ?: "",
            managed = it["managed"] as? Boolean ?: false
        )
    } ?: emptyList()

    fun mapThresholds(m: Map<String, Any>?) = m?.let {
        ThresholdConfig(
            cpu_percent = (it["cpu_percent"] as? Int) ?: 95,
            ram_percent = (it["ram_percent"] as? Int) ?: 90,
            disk_percent = (it["disk_percent"] as? Int) ?: 95,
            temp_celsius = (it["temp_celsius"] as? Int) ?: 85,
            consecutive_failures = (it["consecutive_failures"] as? Int) ?: 2
        )
    } ?: ThresholdConfig()

    fun mapSchedule(m: Map<String, Any>?) = m?.let {
        ScheduleConfig(
            report_hour = (it["report_hour"] as? Int) ?: 9,
            report_minute = (it["report_minute"] as? Int) ?: 0,
            timezone = it["timezone"] as? String ?: "Asia/Ho_Chi_Minh"
        )
    } ?: ScheduleConfig()

    fun mapUpdate(m: Map<String, Any>?) = m?.let {
        UpdateConfig(
            check_interval_minutes = (it["check_interval_minutes"] as? Int)?.toLong() ?: 60L,
            enable_rollback = it["enable_rollback"] as? Boolean ?: true,
            max_rollback_attempts = (it["max_rollback_attempts"] as? Int) ?: 2,
            health_wait_seconds = (it["health_wait_seconds"] as? Int)?.toLong() ?: 120L,
            ota_hour = (it["ota_hour"] as? Int) ?: -1
        )
    } ?: UpdateConfig()

    fun mapOta(m: Map<String, Any>?) = m?.let {
        OtaConfig(
            enabled = it["enabled"] as? Boolean ?: false,
            base_url = it["base_url"] as? String ?: "https://aiview.bkav.com/download",
            manifest_url = it["manifest_url"] as? String ?: "",
            hour = (it["hour"] as? Int) ?: 2,
            jitter_minutes = (it["jitter_minutes"] as? Int) ?: 40,
            auto_apply = it["auto_apply"] as? Boolean ?: true,
            max_download_mbps = (it["max_download_mbps"] as? Int) ?: 20,
            download_retries = (it["download_retries"] as? Int) ?: 5,
            allow_registry_pull = it["allow_registry_pull"] as? Boolean ?: true,
            poll_seconds = (it["poll_seconds"] as? Int)?.toLong() ?: 20L,
        )
    } ?: OtaConfig()

    fun mapSelfHealing(m: Map<String, Any>?) = m?.let {
        SelfHealingConfig(
            max_restarts_per_hour = (it["max_restarts_per_hour"] as? Int) ?: 3,
            disk_cleanup_trigger_percent = (it["disk_cleanup_trigger_percent"] as? Int) ?: 88,
            disk_cleanup_target_percent = (it["disk_cleanup_target_percent"] as? Int) ?: 83
        )
    } ?: SelfHealingConfig()

    fun mapTelegram(m: Map<String, Any>?) = m?.let {
        TelegramConfig(
            bot_token = it["bot_token"] as? String ?: "",
            chat_id = it["chat_id"] as? String ?: ""
        )
    } ?: TelegramConfig()

    fun mapLog(m: Map<String, Any>?) = m?.let {
        LogConfig(
            level = it["level"] as? String ?: "INFO",
            keep_days = (it["keep_days"] as? Int) ?: 90
        )
    } ?: LogConfig()

    @Suppress("UNCHECKED_CAST")
    return WatchdogConfig(
        containers = mapContainers(raw["containers"] as? List<Map<String, Any>>),
        thresholds = mapThresholds(raw["thresholds"] as? Map<String, Any>),
        schedule = mapSchedule(raw["schedule"] as? Map<String, Any>),
        update = mapUpdate(raw["update"] as? Map<String, Any>),
        ota = mapOta(raw["ota"] as? Map<String, Any>),
        self_healing = mapSelfHealing(raw["self_healing"] as? Map<String, Any>),
        telegram = mapTelegram(raw["telegram"] as? Map<String, Any>),
        log = mapLog(raw["log"] as? Map<String, Any>)
    )
}
