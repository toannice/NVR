package vn.aiview.nvr.plugins

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import vn.aiview.nvr.routes.*

fun Application.configureRouting() {
    routing {
        // Public routes
        systemRouting()
        lockRouting()
        
        // Protected routes
        authenticate("auth-session") {
            // Frigate Proxy
            frigateProxyRouting()
            
            // Core features
            cameraRouting()
            scheduleRouting()
            configRouting()
            onvifRouting()
            gatewayRouting()
        }
        
        // Dev/test helpers (active only when E2E_TEST_MODE=true)
        devRouting()

        // Cloud connection (public — internal dc-bridge callback + config endpoints)
        cloudRouting()

        // Static files (for frontend)
        staticFilesRouting()
    }
}
