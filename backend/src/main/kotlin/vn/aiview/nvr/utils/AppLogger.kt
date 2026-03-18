package vn.aiview.nvr.utils

import org.slf4j.LoggerFactory

object AppLogger {
    private val logger = LoggerFactory.getLogger("SystemLog")

    enum class Module {
        CAMERA,
        RECORDING,
        STORAGE,
        EVENT,
        SYSTEM,
        SECURITY,
        PLAYBACK,
        API
    }

    enum class Level {
        ERROR, WARNING, INFO, DEBUG, TRACE
    }

    private fun formatMessage(module: Module, componentId: String, eventType: String, details: String): String {
        return "[$module] [$componentId] [$eventType] | $details"
    }

    fun error(module: Module, componentId: String, eventType: String, details: String, throwable: Throwable? = null) {
        val message = formatMessage(module, componentId, eventType, details)
        if (throwable != null) {
            logger.error(message, throwable)
        } else {
            logger.error(message)
        }
    }

    fun warn(module: Module, componentId: String, eventType: String, details: String, throwable: Throwable? = null) {
        val message = formatMessage(module, componentId, eventType, details)
        if (throwable != null) {
            logger.warn(message, throwable)
        } else {
            logger.warn(message)
        }
    }

    fun info(module: Module, componentId: String, eventType: String, details: String) {
        logger.info(formatMessage(module, componentId, eventType, details))
    }

    fun debug(module: Module, componentId: String, eventType: String, details: String) {
        logger.debug(formatMessage(module, componentId, eventType, details))
    }

    fun trace(module: Module, componentId: String, eventType: String, details: String) {
        logger.trace(formatMessage(module, componentId, eventType, details))
    }
}
