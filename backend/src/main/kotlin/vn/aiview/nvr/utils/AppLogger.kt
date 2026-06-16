package vn.aiview.nvr.utils

import org.slf4j.LoggerFactory

object AppLogger {

    enum class Module(val loggerName: String) {
        CAMERA("CameraLog"),
        CLOUD("CloudLog"),
        GATEWAY("GatewayLog"),
        HEALTH("HealthLog"),
        RECORDING("SystemLog"),
        STORAGE("SystemLog"),
        EVENT("SystemLog"),
        SYSTEM("SystemLog"),
        SECURITY("SystemLog"),
        PLAYBACK("SystemLog"),
        API("SystemLog"),
    }

    private val loggers = Module.entries.associate { it to LoggerFactory.getLogger(it.loggerName) }

    private fun fmt(module: Module, componentId: String, eventType: String, details: String) =
        "[$module] [$componentId] [$eventType] | $details"

    fun error(module: Module, componentId: String, eventType: String, details: String, throwable: Throwable? = null) {
        val msg = fmt(module, componentId, eventType, details)
        val log = loggers[module]!!
        if (throwable != null) log.error(msg, throwable) else log.error(msg)
    }

    fun warn(module: Module, componentId: String, eventType: String, details: String, throwable: Throwable? = null) {
        val msg = fmt(module, componentId, eventType, details)
        val log = loggers[module]!!
        if (throwable != null) log.warn(msg, throwable) else log.warn(msg)
    }

    fun info(module: Module, componentId: String, eventType: String, details: String) {
        loggers[module]!!.info(fmt(module, componentId, eventType, details))
    }

    fun debug(module: Module, componentId: String, eventType: String, details: String) {
        loggers[module]!!.debug(fmt(module, componentId, eventType, details))
    }

    fun trace(module: Module, componentId: String, eventType: String, details: String) {
        loggers[module]!!.trace(fmt(module, componentId, eventType, details))
    }
}
