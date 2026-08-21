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

            // Cập nhật hệ thống: upload gói .nvrup qua UI LAN + theo dõi tiến trình.
            // Backend chỉ nhận file và đọc trạng thái — verify chữ ký và apply là việc của
            // watchdog (xem chú thích đầu OtaRouting.kt về phân vai).
            otaRouting()
        }

        // Dev/test helpers (active only when E2E_TEST_MODE=true)
        devRouting()

        // Cloud connection (public — internal dc-bridge callback + config endpoints)
        cloudRouting()

        // AiView Box integration — public contract endpoints (self-authenticate, v1.1)
        integrationRouting()

        // Box → NVR push event (self-authenticate) — creates event + cuts -5/+5 clip
        pushEventRouting()

        // Camera Bkav → NVR push event (nhận diện chạy trên thân camera) — cùng đường cắt clip -5/+5
        bkavEventRouting()

        // ONVIF server — NVR advertises itself as ONVIF device for Box/VMS discovery (HTTP, no session auth)
        onvifServerRouting()

        // Static files (for frontend)
        staticFilesRouting()
    }
}
