package vn.aiview.nvr

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import vn.aiview.nvr.plugins.*
import vn.aiview.nvr.services.CloudService
import vn.aiview.nvr.services.IntegrationService
import vn.aiview.nvr.utils.AppLogger
import java.io.File

fun main() {
    // Ensure data directory exists
    val dataDir = File("data")
    if (!dataDir.exists()) {
        dataDir.mkdirs()
    }
    
    AppLogger.info(AppLogger.Module.SYSTEM, "CORE", "SYSTEM_START", "Starting NVR Wrapper | Mode: Normal")

    val port = System.getenv("PORT")?.toIntOrNull() ?: 3000

    // Bind loopback by default. The container runs with network_mode: host, so binding
    // 0.0.0.0 published the whole API straight onto the LAN and let anyone skip nginx
    // (and TLS) with http://<ip>:3000. nginx, dc-bridge and the watchdog all reach us
    // over 127.0.0.1, so nothing legitimate needs the wildcard.
    // Set BIND_HOST=0.0.0.0 only for a deliberate, temporary debug session.
    val bindHost = System.getenv("BIND_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
    AppLogger.info(AppLogger.Module.SYSTEM, "CORE", "SYSTEM_START", "HTTP listening on $bindHost:$port")

    embeddedServer(Netty, port = port, host = bindHost, module = Application::module)
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
    
    vn.aiview.nvr.services.HealthMonitorService.start()
    vn.aiview.nvr.services.MqttService.start()
    vn.aiview.nvr.services.RecordingScheduler.start()
    vn.aiview.nvr.services.GatewayScanner.start()
    vn.aiview.nvr.services.GatewayMonitor.start()
    vn.aiview.nvr.services.StreamOnlineTracker.start()
    CloudService.start()
    IntegrationService.applyBoxClient()
    vn.aiview.nvr.services.OnvifServerService.start()
}
