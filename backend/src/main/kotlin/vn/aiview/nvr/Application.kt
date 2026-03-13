package vn.aiview.nvr

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import vn.aiview.nvr.plugins.*
import java.io.File

fun main() {
    // Ensure data directory exists
    val dataDir = File("data")
    if (!dataDir.exists()) {
        dataDir.mkdirs()
    }

    val port = System.getenv("PORT")?.toIntOrNull() ?: 3000
    
    embeddedServer(Netty, port = port, host = "127.0.0.1", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    configureSecurity()
    configureMonitoring()
    configureSerialization()
    configureHTTP()
    configureDatabases()
    configureSockets()
    configureRouting()
    
    vn.aiview.nvr.services.MqttService.start()
    vn.aiview.nvr.services.RecordingScheduler.start()
}
