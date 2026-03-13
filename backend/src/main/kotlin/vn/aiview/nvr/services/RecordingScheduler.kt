package vn.aiview.nvr.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.Schedules
import vn.aiview.nvr.models.Cameras
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json

import org.slf4j.LoggerFactory

object RecordingScheduler {
    private val logger = LoggerFactory.getLogger(RecordingScheduler::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)
    
    fun start() {
        scope.launch {
            logger.info("Recording Scheduler starting in 5 seconds...")
            delay(5_000) // Give MQTT some time to connect
            var tickCount = 0
            while (isActive) {
                try {
                    // tickCount % 10 == 0 means every 10 minutes (assuming 60s delay)
                    val forceRefresh = tickCount % 10 == 0
                    checkAndApplySchedules(forceRefresh)
                    tickCount++
                } catch (e: Exception) {
                    logger.error("Error in recording scheduler: ${e.message}", e)
                }
                // Check every 60 seconds
                delay(60_000)
            }
        }
    }

    private val lastStates = mutableMapOf<String, String>()

    private fun checkAndApplySchedules(forceRefresh: Boolean = false) {
        val now = LocalDateTime.now()
        val currentDayStr = now.dayOfWeek.name.lowercase() // "monday", "tuesday"...
        val currentTimeStr = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        
        transaction {
            val cameraInfo = Cameras.selectAll().map { 
                it[Cameras.slug] to (it[Cameras.displayName] ?: it[Cameras.slug])
            }
            val allSchedules = mutableListOf<ScheduleInfo>()
            Schedules.selectAll().forEach { row ->
                try {
                    val decodedDays = Json.decodeFromString<List<String>>(row[Schedules.days]).map { it.lowercase() }
                    allSchedules.add(
                        ScheduleInfo(
                            cameraSlug = row[Schedules.cameraSlug],
                            days = decodedDays,
                            startTime = row[Schedules.startTime],
                            endTime = row[Schedules.endTime]
                        )
                    )
                } catch (e: Exception) {
                    logger.error("Failed to parse days JSON for schedule: ${row[Schedules.id]} - ${e.message}")
                }
            }
            
            for ((slug, displayName) in cameraInfo) {
                // Try to match by slug OR display name
                val camSchedules = allSchedules.filter { it.cameraSlug == slug || it.cameraSlug == displayName }
                
                val targetState = if (camSchedules.isEmpty()) {
                    "ON" // Default to ON if no schedule
                } else {
                    val activeSchedule = camSchedules.find { schedule ->
                        schedule.days.contains(currentDayStr) && isTimeInRange(currentTimeStr, schedule.startTime, schedule.endTime)
                    }
                    if (activeSchedule != null) "ON" else "OFF"
                }
                
                // Publish if state changed OR if we are doing a periodic refresh
                if (lastStates[slug] != targetState || forceRefresh) {
                    val reason = if (forceRefresh && lastStates[slug] == targetState) "Periodic Sync" else "State Change"
                    logger.info("[$reason] $slug ($displayName) -> $targetState (Day: $currentDayStr, Time: $currentTimeStr)")
                    MqttService.publish("frigate/$slug/recordings/set", targetState, retained = false)
                    lastStates[slug] = targetState
                }
            }
        }
    }

    private fun isTimeInRange(current: String, start: String, end: String): Boolean {
        try {
            val currentParts = current.split(":")
            val startParts = start.split(":")
            val endParts = end.split(":")
            
            if (currentParts.size != 2 || startParts.size != 2 || endParts.size != 2) return false
            
            val currentMins = currentParts[0].toInt() * 60 + currentParts[1].toInt()
            val startMins = startParts[0].toInt() * 60 + startParts[1].toInt()
            val endMins = endParts[0].toInt() * 60 + endParts[1].toInt()

            return if (startMins <= endMins) {
                currentMins in startMins..endMins
            } else {
                currentMins >= startMins || currentMins <= endMins
            }
        } catch (e: Exception) {
            logger.error("Failed to compare time: ${e.message}")
            return false
        }
    }
    
    private data class ScheduleInfo(
        val cameraSlug: String,
        val days: List<String>,
        val startTime: String,
        val endTime: String
    )
}
