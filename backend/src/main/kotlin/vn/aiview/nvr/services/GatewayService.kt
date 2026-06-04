package vn.aiview.nvr.services

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import vn.aiview.nvr.gateway.EdgeAgentClient
import vn.aiview.nvr.gateway.EdgeDevice
import vn.aiview.nvr.gateway.EdgeHealth
import vn.aiview.nvr.gateway.GatewayConfig
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
    val agentPort: Int,
    val status: String,         // discovered | approved
    val online: Boolean,        // derived from lastSeen
    val fieldSubnet: String?,
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

    data class Conn(val ip: String, val port: Int, val token: String)

    // ── discovery / liveness (called by GatewayScanner) ────────────────────────

    /** Insert a newly-seen gateway as `discovered`, or refresh an existing one's metadata + lastSeen.
     *  Never downgrades an `approved` gateway. */
    fun upsertFromHealth(ip: String, h: EdgeHealth) {
        if (h.gatewayId.isBlank()) return
        transaction {
            val existing = Gateways.selectAll().where { Gateways.id eq h.gatewayId }.firstOrNull()
            if (existing == null) {
                Gateways.insert {
                    it[Gateways.id] = h.gatewayId
                    it[Gateways.name] = h.gatewayName.ifBlank { ip }
                    it[Gateways.zerotierIp] = ip
                    it[Gateways.agentPort] = if (h.agentPort > 0) h.agentPort else GatewayConfig.agentPort
                    it[Gateways.status] = "discovered"
                    it[Gateways.fieldSubnet] = h.fieldSubnet.ifBlank { null }
                    it[Gateways.lastSeen] = LocalDateTime.now()
                }
                AppLogger.info(AppLogger.Module.SYSTEM, "GATEWAY", "DISCOVERED", "id=${h.gatewayId} ip=$ip name=${h.gatewayName}")
            } else {
                Gateways.update({ Gateways.id eq h.gatewayId }) {
                    it[Gateways.name] = h.gatewayName.ifBlank { existing[Gateways.name] }
                    it[Gateways.zerotierIp] = ip
                    if (h.agentPort > 0) it[Gateways.agentPort] = h.agentPort
                    if (h.fieldSubnet.isNotBlank()) it[Gateways.fieldSubnet] = h.fieldSubnet
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
                agentPort = row[Gateways.agentPort],
                status = row[Gateways.status],
                online = lastSeen != null && Duration.between(lastSeen, LocalDateTime.now()) < ONLINE_WINDOW,
                fieldSubnet = row[Gateways.fieldSubnet],
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
}
