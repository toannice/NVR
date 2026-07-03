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
    val self_healing: SelfHealingConfig = SelfHealingConfig(),
    val telegram: TelegramConfig = TelegramConfig(),
    val log: LogConfig = LogConfig()
)

fun loadConfig(path: String): WatchdogConfig {
    val yaml = Yaml()
    // WRAPPER_TAG controls the OTA target tag (latest=stable / staging=beta). Single source of
    // truth: it is injected into the watchdog container from .env in docker-compose.yml, and both
    // the forward update and the rollback read the resolved image — no hardcoded tag anywhere.
    val rawText = File(path).readText()
        .replace("\${TELEGRAM_BOT_TOKEN}", System.getenv("TELEGRAM_BOT_TOKEN") ?: "")
        .replace("\${TELEGRAM_CHAT_ID}", System.getenv("TELEGRAM_CHAT_ID") ?: "")
        .replace("\${WRAPPER_TAG}", System.getenv("WRAPPER_TAG") ?: "latest")

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
        self_healing = mapSelfHealing(raw["self_healing"] as? Map<String, Any>),
        telegram = mapTelegram(raw["telegram"] as? Map<String, Any>),
        log = mapLog(raw["log"] as? Map<String, Any>)
    )
}
