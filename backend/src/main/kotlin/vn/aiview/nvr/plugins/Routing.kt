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

            // AiView Box integration — admin settings (enable + credentials)
            integrationAdminRouting()

            // AI event list + clip/snapshot serving + source-mode (5B) toggle
            eventsRouting()
        }

        // Dev/test helpers (active only when E2E_TEST_MODE=true)
        devRouting()

        // Cloud connection (public — internal dc-bridge callback + config endpoints)
        cloudRouting()

        // AiView Box integration — public contract endpoints (self-authenticate, v1.1)
        integrationRouting()

        // Box → NVR push event (self-authenticate) — creates event + cuts -5/+5 clip
        pushEventRouting()

        // ONVIF server — NVR advertises itself as ONVIF device for Box/VMS discovery (HTTP, no session auth)
        onvifServerRouting()

        // Static files (for frontend)
        staticFilesRouting()
    }
}
