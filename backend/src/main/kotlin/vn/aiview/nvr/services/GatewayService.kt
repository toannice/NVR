package vn.aiview.nvr.services

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import vn.aiview.nvr.gateway.EdgeAgentClient
import vn.aiview.nvr.gateway.EdgeDevice
import vn.aiview.nvr.gateway.EdgeHealth
import vn.aiview.nvr.gateway.GatewayConfig
import vn.aiview.nvr.gateway.ZtControllerClient
import vn.aiview.nvr.gateway.NebulaCaClient
import vn.aiview.nvr.gateway.NebulaPending
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.models.Gateways
import vn.aiview.nvr.utils.AppLogger
import java.time.Duration
import java.time.LocalDateTime

@Serializable
data class GatewayDto(
    val id: String,
    val name: String,
    val zerotierIp: String?,
    val ztAddress: String?,      // 10-char ZeroTier node address (what the admin approves on ztncui)
    val agentPort: Int,
    val status: String,         // discovered | approved
    val online: Boolean,        // derived from lastSeen
    val fieldSubnet: String?,
    val installer: String?,      // field-worker label from the Android app
    val location: String?,       // site label from the Android app
    val lastSeenEpoch: Long?,    // epoch seconds, null if never
    val cameraCount: Int,
)

/**
 * NVR-side gateway lifecycle (4-6 Plan A). The gateway is passive: it never contacts us, so all
 * of this is NVR-initiated. ZeroTier member authorization is done out-of-band in ZeroTier Central
 * (no ZT API token stored here). Adoption reuses [CameraService.applyAndPersistFromOnvif].
 */
object GatewayService {

    /** A gateway counts as online if its agent answered /health recently. */
    private val ONLINE_WINDOW: Duration get() = Duration.ofSeconds(GatewayConfig.scanIntervalSec * 3)

    /** Node addresses the admin just authorized from the NVR ("Cấp phép"). When the scanner later
     *  discovers the matching gateway we approve it immediately, so one click does BOTH the ZeroTier
     *  authorization and the NVR adoption gate — no redundant second "Phê duyệt". */
    private val pendingAutoApprove: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    data class Conn(val ip: String, val port: Int, val token: String)

    // ── discovery / liveness (called by GatewayScanner) ────────────────────────

    /** Insert a newly-seen gateway as `discovered`, or refresh an existing one's metadata + lastSeen.
     *  Never downgrades an `approved` gateway. */
    fun upsertFromHealth(ip: String, h: EdgeHealth) {
        if (h.gatewayId.isBlank()) return
        transaction {
            val existing = Gateways.selectAll().where { Gateways.id eq h.gatewayId }.firstOrNull()
            if (existing == null) {
                // If the admin authorized this node from the NVR, skip the manual "Phê duyệt" gate.
                val autoApproved = h.ztAddress.isNotBlank() && pendingAutoApprove.remove(h.ztAddress)
                Gateways.insert {
                    it[Gateways.id] = h.gatewayId
                    it[Gateways.name] = h.gatewayName.ifBlank { ip }
                    it[Gateways.zerotierIp] = ip
                    it[Gateways.agentPort] = if (h.agentPort > 0) h.agentPort else GatewayConfig.agentPort
                    it[Gateways.status] = if (autoApproved) "approved" else "discovered"
                    it[Gateways.fieldSubnet] = h.fieldSubnet.ifBlank { null }
                    it[Gateways.ztAddress] = h.ztAddress.ifBlank { null }
                    it[Gateways.installer] = h.installer.ifBlank { null }
                    it[Gateways.location] = h.location.ifBlank { null }
                    it[Gateways.lastSeen] = LocalDateTime.now()
                }
                AppLogger.info(AppLogger.Module.SYSTEM, "GATEWAY", if (autoApproved) "DISCOVERED_AUTOAPPROVED" else "DISCOVERED", "id=${h.gatewayId} ip=$ip name=${h.gatewayName}")
            } else {
                Gateways.update({ Gateways.id eq h.gatewayId }) {
                    it[Gateways.name] = h.gatewayName.ifBlank { existing[Gateways.name] }
                    it[Gateways.zerotierIp] = ip
                    if (h.agentPort > 0) it[Gateways.agentPort] = h.agentPort
                    if (h.fieldSubnet.isNotBlank()) it[Gateways.fieldSubnet] = h.fieldSubnet
                    if (h.ztAddress.isNotBlank()) it[Gateways.ztAddress] = h.ztAddress
                    if (h.installer.isNotBlank()) it[Gateways.installer] = h.installer
                    if (h.location.isNotBlank()) it[Gateways.location] = h.location
                    it[Gateways.lastSeen] = LocalDateTime.now()
                    it[Gateways.updatedAt] = LocalDateTime.now()
                }
            }
        }
    }

    // ── admin actions ───────────────────────────────────────────────────────────

    fun listGateways(): List<GatewayDto> = transaction {
        val counts = Cameras.selectAll().mapNotNull { it[Cameras.gatewayId] }
            .groupingBy { it }.eachCount()
        Gateways.selectAll().map { row ->
            val id = row[Gateways.id]
            val lastSeen = row[Gateways.lastSeen]
            GatewayDto(
                id = id,
                name = row[Gateways.name],
                zerotierIp = row[Gateways.zerotierIp],
                ztAddress = row[Gateways.ztAddress],
                agentPort = row[Gateways.agentPort],
                status = row[Gateways.status],
                online = lastSeen != null && Duration.between(lastSeen, LocalDateTime.now()) < ONLINE_WINDOW,
                fieldSubnet = row[Gateways.fieldSubnet],
                installer = row[Gateways.installer],
                location = row[Gateways.location],
                lastSeenEpoch = lastSeen?.atZone(java.time.ZoneId.systemDefault())?.toEpochSecond(),
                cameraCount = counts[id] ?: 0,
            )
        }
    }

    fun approve(id: String): Boolean = transaction {
        Gateways.update({ Gateways.id eq id }) {
            it[Gateways.status] = "approved"
            it[Gateways.updatedAt] = LocalDateTime.now()
        } > 0
    }

    // ── ZeroTier member authorization (approve a brand-new gateway from the NVR, no ztncui) ─────

    @Serializable
    data class PendingMemberDto(val nodeId: String, val ipAssignments: List<String>)

    /** Is the controller integration wired (url + network id + token)? Drives whether the UI
     *  shows the "pending ZeroTier members" section at all. */
    fun controllerConfigured(): Boolean = ZtControllerClient.isConfigured()

    /**
     * Members the controller knows but that are NOT yet authorized — i.e. freshly-booted gateways
     * waiting to be let onto the overlay. Already-authorized members (other gateways, NVRs) are
     * excluded; so are any whose node address already maps to a gateway row we track.
     */
    suspend fun listPending(): List<PendingMemberDto> {
        val members = ZtControllerClient.listMembers() ?: return emptyList()
        val known = transaction { Gateways.selectAll().mapNotNull { it[Gateways.ztAddress] }.toSet() }
        return members
            .filter { !it.authorized && it.nodeId !in known }
            .map { PendingMemberDto(it.nodeId, it.ipAssignments) }
    }

    /** Authorize a pending member; controller assigns its IP and the scanner picks it up shortly. */
    suspend fun authorizeMember(nodeId: String): Boolean {
        val ok = ZtControllerClient.authorize(nodeId)
        if (ok) {
            pendingAutoApprove.add(nodeId)   // auto-approve it once the scanner discovers it
            AppLogger.info(AppLogger.Module.SYSTEM, "GATEWAY", "ZT_AUTHORIZED", "node=$nodeId")
        }
        return ok
    }

    // ── Nebula control plane (parallel to ZeroTier; active when NEBULA_CA_URL is set) ────────────
    // These mirror controllerConfigured/listPending/authorizeMember but drive the HQ signing service
    // (NebulaCaClient) instead of the ZeroTier controller: "Cấp phép" SIGNS the gateway's cert. The
    // routes/scanner pick the active overlay via GatewayConfig.nebulaEnabled. ZeroTier path untouched.

    fun nebulaConfigured(): Boolean = NebulaCaClient.isConfigured()

    /** Gateways that enrolled on the HQ signing service and await admin "Cấp phép" (Nebula). */
    suspend fun listPendingNebula(): List<NebulaPending> = NebulaCaClient.listPending() ?: emptyList()

    /**
     * Approve ("Cấp phép") a pending Nebula gateway by its enrollment token → the signing service signs
     * its cert; the polling agent applies it + starts nebula, and the scanner discovers the new overlay
     * node shortly (inserted as approved, like DISCOVERED_AUTOAPPROVED).
     */
    suspend fun approveNebula(token: String): Boolean {
        val ok = NebulaCaClient.approve(token)
        if (ok) AppLogger.info(AppLogger.Module.SYSTEM, "GATEWAY", "NEBULA_APPROVED", "token=${token.take(6)}..")
        return ok
    }

    private fun conn(id: String): Conn? = transaction {
        Gateways.selectAll().where { Gateways.id eq id }.firstOrNull()?.let { row ->
            val ip = row[Gateways.zerotierIp] ?: return@let null
            Conn(ip, row[Gateways.agentPort], GatewayConfig.token)
        }
    }

    suspend fun discover(id: String): List<EdgeDevice>? {
        val c = conn(id) ?: return null
        return EdgeAgentClient.discover(c.ip, c.port, c.token)
    }

    /** Cameras already proxied on the agent (available for adoption). */
    suspend fun adoptableCameras(id: String): List<vn.aiview.nvr.gateway.EdgeCamera>? {
        val c = conn(id) ?: return null
        return EdgeAgentClient.listCameras(c.ip, c.port, c.token)
    }

    /**
     * Adopt a field camera through the gateway: ensure its proxy ports exist (idempotent), then run
     * the standard ONVIF probe over the proxy, rewriting RTSP to the gateway, and auto-detect PTZ.
     * Credentials are supplied by the NVR admin here (the gateway never stores them).
     */
    suspend fun adoptCamera(gatewayId: String, localIp: String, user: String, pass: String, name: String): Result<AppliedCamera> {
        val c = conn(gatewayId) ?: return Result.failure(IllegalStateException("Gateway not found or has no ZeroTier IP"))

        val proxy = EdgeAgentClient.createProxy(c.ip, c.port, c.token, localIp, name)
            ?: return Result.failure(IllegalStateException("Agent did not allocate proxy ports for $localIp"))

        val channelId = transaction {
            val used = Cameras.selectAll().mapNotNull { it[Cameras.channelId]?.toIntOrNull() }.toSet()
            var next = 1
            while (next in used) next++
            next.toString()
        }

        val applied = CameraService.applyAndPersistFromOnvif(
            channelId = channelId,
            host = c.ip,
            port = proxy.onvifPort,
            user = user,
            pass = pass,
            displayName = name,
            rtspProxyHostPort = "${c.ip}:${proxy.rtspPort}",
            gatewayId = gatewayId,
            localIp = localIp,
            assignedRtspPort = proxy.rtspPort,
        ).getOrElse { return Result.failure(it) }

        // Best-effort empirical PTZ detection (doesn't block, won't override manual ptz_mode).
        runCatching { CameraService.autoDetectPtz(applied.slug, c.ip, proxy.onvifPort, user, pass) }

        AppLogger.info(AppLogger.Module.CAMERA, "GATEWAY", "ADOPT_CAMERA",
            "gateway=$gatewayId local=$localIp onvif=${proxy.onvifPort} rtsp=${proxy.rtspPort} slug=${applied.slug}")
        return Result.success(applied)
    }

    @Serializable
    data class DeleteResult(val deleted: Boolean, val camerasRemoved: Int)

    /** A camera adopted through a gateway, as the NVR stores it (for the gateway panel's camera list). */
    @Serializable
    data class AdoptedCameraDto(
        val slug: String,
        val name: String,
        val localIp: String?,    // camera IP on the field LAN (what the agent proxies)
        val channelId: String?,
        val ptzEnabled: Boolean = false,
        val ptzMode: String? = null,   // canonical 3-state: null/"none" | "zoom" | "full"
    )

    /** Cameras the NVR adopted through this gateway (read from our DB, NOT the agent). Drives the
     *  "camera đã thêm" list + per-camera delete in the gateway panel. */
    fun adoptedCameras(id: String): List<AdoptedCameraDto> = transaction {
        Cameras.selectAll().where { Cameras.gatewayId eq id }.map {
            AdoptedCameraDto(
                slug = it[Cameras.slug],
                name = it[Cameras.displayName],
                localIp = it[Cameras.localIp],
                channelId = it[Cameras.channelId],
                ptzEnabled = it[Cameras.ptzEnabled],
                ptzMode = it[Cameras.ptzMode],
            )
        }
    }

    /**
     * Remove a SINGLE camera adopted through a gateway (vs [deleteGateway] which removes all of them).
     * Frees the agent's nginx proxy port for that camera (best-effort — the gateway may be offline,
     * which must not block removal) then runs [CameraService.removeAndPersist] (DB + YAML + go2rtc +
     * Frigate restart). Returns null if the gateway is unknown or the slug isn't a camera of this
     * gateway (so the caller can answer 404 rather than silently no-op).
     */
    suspend fun removeCamera(gatewayId: String, slug: String): DeleteResult? {
        // Camera must exist AND belong to this gateway — guards against deleting an unrelated camera.
        val localIp = transaction {
            Cameras.selectAll()
                .where { (Cameras.slug eq slug) and (Cameras.gatewayId eq gatewayId) }
                .firstOrNull()?.get(Cameras.localIp)
        } ?: return null

        val c = conn(gatewayId)
        if (c != null && !localIp.isNullOrBlank()) {
            runCatching { EdgeAgentClient.deleteProxy(c.ip, c.port, c.token, localIp) }
        }

        val ok = CameraService.removeAndPersist(slug).isSuccess
        AppLogger.info(AppLogger.Module.CAMERA, "GATEWAY", "DELETE_CAMERA",
            "gateway=$gatewayId slug=$slug local=$localIp ok=$ok")
        return DeleteResult(ok, if (ok) 1 else 0)
    }

    /**
     * Delete a gateway and CASCADE-remove every camera adopted through it (DB + YAML + go2rtc + Frigate
     * restart, via [CameraService.removeAndPersist]). Best-effort frees the agent's nginx proxy ports
     * (`DELETE /camera`) — the gateway may be offline, which must not block deletion. Returns null if the
     * gateway id is unknown.
     */
    suspend fun deleteGateway(id: String): DeleteResult? {
        val exists = transaction { Gateways.selectAll().where { Gateways.id eq id }.firstOrNull() != null }
        if (!exists) return null

        // Snapshot the gateway's cameras (slug + localIp) before we start mutating.
        val cams = transaction {
            Cameras.selectAll().where { Cameras.gatewayId eq id }
                .map { it[Cameras.slug] to it[Cameras.localIp] }
        }
        val c = conn(id)

        var removed = 0
        for ((slug, localIp) in cams) {
            // Free the proxy port on the agent first (best-effort; ignored if the gateway is unreachable).
            if (c != null && !localIp.isNullOrBlank()) {
                runCatching { EdgeAgentClient.deleteProxy(c.ip, c.port, c.token, localIp) }
            }
            CameraService.removeAndPersist(slug).onSuccess { removed++ }
                .onFailure { AppLogger.warn(AppLogger.Module.CAMERA, "GATEWAY", "DELETE_CAM_FAIL", "slug=$slug: ${it.message}") }
        }

        transaction { Gateways.deleteWhere { Gateways.id eq id } }
        AppLogger.info(AppLogger.Module.SYSTEM, "GATEWAY", "DELETED", "id=$id camerasRemoved=$removed/${cams.size}")
        return DeleteResult(true, removed)
    }
}
