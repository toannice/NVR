package vn.aiview.nvr.routes

import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import java.io.File

fun Route.staticFilesRouting() {
    val pathsToTry = listOf(
        "/app/frontend/dist",
        "frontend/dist",
        "../frontend/dist",
        "../../frontend/dist"
    )
    val staticDir = pathsToTry.map { File(it) }.firstOrNull { it.exists() && it.isDirectory }
    
    val exportsDir = File("/app/media/exports")
    if (exportsDir.exists() || exportsDir.mkdirs()) {
        staticFiles("/media/exports", exportsDir)
    }

    val screenshotsDir = File("/app/media/screenshots")
    if (screenshotsDir.exists() || screenshotsDir.mkdirs()) {
        staticFiles("/media/screenshots", screenshotsDir)
    }
    if (staticDir != null) {
        staticFiles("/", staticDir) {
            default("index.html")
        }
    } else {
        get("/{...}") {
            call.respondText("Frontend build directory not found. Expected it in $pathsToTry")
        }
    }
}
