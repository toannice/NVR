package vn.aiview.nvr.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import vn.aiview.nvr.services.GatewayScanner
import vn.aiview.nvr.services.GatewayService

@Serializable
private data class AdoptResponse(
    val success: Boolean,
    val slug: String,
    val channelId: String,
    val frigateRestarted: Boolean,
)

@Serializable
private data class DeleteGatewayResponse(
    val success: Boolean,
    val camerasRemoved: Int,
)

@Serializable
private data class PendingResponse(
    val configured: Boolean,
    val members: List<GatewayService.PendingMemberDto>,
)

/**
 * Admin-facing gateway endpoints (4-6 Plan A). All NVR-initiated; there is intentionally NO
 * /register or /pending-cameras endpoint — the gateway never pushes to us.
 */
fun Route.gatewayRouting() {
    route("/api/gateways") {

        get {
            call.respond(GatewayService.listGateways())
        }

        // Trigger an immediate subnet sweep (the scanner also runs periodically on its own).
        post("/scan") {
            val found = GatewayScanner.scanNow()
            call.respond(mapOf("found" to found))
        }

        // ZeroTier members waiting to be authorized (brand-new gateways not yet on the overlay).
        // `configured=false` when the controller integration isn't wired — UI hides the section.
        get("/pending") {
            if (!GatewayService.controllerConfigured()) {
                call.respond(PendingResponse(false, emptyList()))
            } else {
                call.respond(PendingResponse(true, GatewayService.listPending()))
            }
        }

        // Authorize a ZeroTier member straight from the NVR (replaces the manual ztncui click).
        post("/authorize/{nodeId}") {
            val nodeId = call.parameters["nodeId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing nodeId"))
            if (GatewayService.authorizeMember(nodeId)) call.respond(mapOf("success" to true))
            else call.respond(HttpStatusCode.BadGateway, mapOf("error" to "controller authorize failed"))
        }

        // Delete a gateway + cascade-remove its adopted cameras (DB+YAML+go2rtc+Frigate restart).
        delete("/{id}") {
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing id"))
            val res = GatewayService.deleteGateway(id)
                ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "gateway not found"))
            call.respond(DeleteGatewayResponse(res.deleted, res.camerasRemoved))
        }

        // Cameras the NVR adopted through this gateway (from our DB) — drives the per-camera list/delete.
        get("/{id}/cameras") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing id"))
            call.respond(mapOf("cameras" to GatewayService.adoptedCameras(id)))
        }

        // Delete a SINGLE adopted camera (frees the agent proxy + DB/YAML/go2rtc/Frigate restart).
        delete("/{id}/cameras/{slug}") {
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing id"))
            val slug = call.parameters["slug"] ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing slug"))
            val res = GatewayService.removeCamera(id, slug)
                ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "camera not found on this gateway"))
            call.respond(DeleteGatewayResponse(res.deleted, res.camerasRemoved))
        }

        post("/{id}/approve") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing id"))
            if (GatewayService.approve(id)) call.respond(mapOf("success" to true))
            else call.respond(HttpStatusCode.NotFound, mapOf("error" to "gateway not found"))
        }

        // Live WS-Discovery on the field LAN (proxied through the agent).
        post("/{id}/discover") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing id"))
            val devices = GatewayService.discover(id)
                ?: return@post call.respond(HttpStatusCode.BadGateway, mapOf("error" to "agent unreachable or no ZeroTier IP"))
            call.respond(mapOf("devices" to devices))
        }

        // Cameras already proxied on the agent and available to adopt.
        get("/{id}/adoptable") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing id"))
            val cams = GatewayService.adoptableCameras(id)
                ?: return@get call.respond(HttpStatusCode.BadGateway, mapOf("error" to "agent unreachable"))
            call.respond(mapOf("cameras" to cams))
        }

        // Adopt a field camera. Admin supplies ONVIF creds here (gateway never stores them).
        post("/{id}/cameras") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing id"))
            val json = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid body"))
            val localIp = json["localIp"]?.jsonPrimitive?.content
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "localIp required"))
            val user = json["username"]?.jsonPrimitive?.content ?: "admin"
            val pass = json["password"]?.jsonPrimitive?.content ?: ""
            val name = json["name"]?.jsonPrimitive?.content ?: localIp

            GatewayService.adoptCamera(id, localIp, user, pass, name).fold(
                onSuccess = { a ->
                    call.respond(AdoptResponse(true, a.slug, a.channelId, a.frigateRestarted))
                },
                onFailure = { e ->
                    call.respond(HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "adopt failed")))
                }
            )
        }
    }
}
