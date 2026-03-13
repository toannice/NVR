package vn.aiview.nvr.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.HttpStatusCode
import org.yaml.snakeyaml.Yaml
import java.io.File

fun Route.configRouting() {
    route("/api/config") {
        get {
            val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
            val file = File(configPath)
            if (!file.exists()) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config file not found"))
                return@get
            }
            call.respondFile(file)
        }
        
        // Note: Writing YAML back is a bit complex in Kotlin without losing comments, 
        // snakeyaml can do it but it overwrites formatting. For now, simple dump.
        post {
            try {
                val newConfigStr = call.receiveText()
                // Validate it's parseable YAML first
                val yaml = Yaml()
                val parsed = yaml.load<Map<String, Any>>(newConfigStr)
                if (parsed == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid YAML data"))
                    return@post
                }
                
                val configPath = System.getenv("CONFIG_PATH") ?: "config/config.yml"
                val file = File(configPath)
                file.writeText(newConfigStr) // Save exactly what was sent (which should be formatted correctly by frontend)
                call.respond(mapOf("success" to true))
                
                // TODO: Restart frigate container (ProcessBuilder("docker", "restart", "frigate")) if needed
            } catch (e: Exception) {
                call.application.environment.log.error("Failed to save config", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to save configuration"))
            }
        }
    }
}
