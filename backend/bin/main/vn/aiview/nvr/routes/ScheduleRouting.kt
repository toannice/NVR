package vn.aiview.nvr.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.Schedules
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

@Serializable
data class ScheduleData(
    val id: String? = null,
    val camera: String? = null, // Frontend sends 'camera'
    val cameraSlug: String? = null, // Backend uses 'cameraSlug'
    val days: List<String>,
    val startTime: String,
    val endTime: String
)

fun Route.scheduleRouting() {
    route("/api/recording-schedules") {
        get {
            try {
                val results = transaction {
                    Schedules.selectAll().map {
                        ScheduleData(
                            id = it[Schedules.id],
                            camera = it[Schedules.cameraSlug],
                            cameraSlug = it[Schedules.cameraSlug],
                            days = Json.decodeFromString(it[Schedules.days]),
                            startTime = it[Schedules.startTime],
                            endTime = it[Schedules.endTime]
                        )
                    }
                }
                call.respond(results)
            } catch (e: Exception) {
                call.application.environment.log.error("Failed to fetch schedules", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to fetch schedules"))
            }
        }

        post {
            try {
                val body = call.receive<ScheduleData>()
                val slug = body.camera ?: body.cameraSlug ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing camera identifier"))
                val id = body.id ?: java.util.UUID.randomUUID().toString()
                
                transaction {
                    Schedules.insert {
                        it[Schedules.id] = id
                        it[Schedules.cameraSlug] = slug
                        it[Schedules.days] = Json.encodeToString(body.days)
                        it[Schedules.startTime] = body.startTime
                        it[Schedules.endTime] = body.endTime
                    }
                }
                call.respond(HttpStatusCode.Created, mapOf("success" to true))
            } catch (e: Exception) {
                call.application.environment.log.error("Failed to save schedule", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to save schedule: ${e.message}"))
            }
        }

        // Handle both /api/recording-schedules/{cameraName}/{id} and /api/recording-schedules/{id}
        delete("/{param1}/{param2?}") {
            try {
                val p1 = call.parameters["param1"]
                val p2 = call.parameters["param2"]
                
                transaction {
                    if (p2 != null) {
                        // /api/recording-schedules/{cameraName}/{id}
                        Schedules.deleteWhere { (Schedules.cameraSlug eq p1!!) and (Schedules.id eq p2) }
                    } else {
                        // /api/recording-schedules/{id} OR /api/recording-schedules/{cameraName}
                        // We try to match by ID first, then by cameraSlug if no match?
                        // Actually frontend sends /api/recording-schedules/${cameraName} if no scheduleId
                        // So let's delete all for that camera
                        val deleted = Schedules.deleteWhere { (Schedules.id eq p1!!) or (Schedules.cameraSlug eq p1!!) }
                    }
                }
                call.respond(mapOf("success" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to delete schedule"))
            }
        }
    }
}
