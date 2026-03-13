package vn.aiview.nvr.plugins

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import vn.aiview.nvr.services.MqttService

// Global flow to broadcast JSON strings to all connected WebSocket clients
val mqttEventFlow = MutableSharedFlow<String>(extraBufferCapacity = 100)

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    routing {
        webSocket("/ws/mqtt") {
            // New connection
            // 1. Send all currently active detections immediately
            val activeStateJson = MqttService.getActiveDetectionsCurrentStateJson()
            if (activeStateJson.isNotEmpty()) {
                send(Frame.Text(activeStateJson))
            }
            
            // 2. Stream new events
            val collectJob = launch {
                mqttEventFlow.collect { message ->
                    send(Frame.Text(message))
                }
            }
            
            try {
                // Keep connection alive, consume incoming (if any, though we only push)
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        if (text == "ping") {
                            send(Frame.Text("pong"))
                        }
                    }
                }
            } finally {
                collectJob.cancel()
            }
        }
    }
}
