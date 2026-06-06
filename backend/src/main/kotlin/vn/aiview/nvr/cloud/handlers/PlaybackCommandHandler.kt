package vn.aiview.nvr.cloud.handlers

import kotlinx.serialization.json.*
import vn.aiview.nvr.cloud.CommandDispatcher

object PlaybackCommandHandler {
    fun handle(func: String, data: JsonObject): JsonObject = when (func) {
        "get_recording_recordingjob" ->
            CommandDispatcher.successResponse(buildJsonObject { put("recordingjobs", buildJsonArray {}) })
        "get_search_findrecordings" ->
            CommandDispatcher.successResponse(buildJsonObject { put("recordings", buildJsonArray {}) })
        "post_search_calendar" ->
            CommandDispatcher.successResponse(buildJsonObject { put("dates", buildJsonArray {}) })
        else -> CommandDispatcher.errorResponse("E0008", "Function not supported: $func")
    }
}
