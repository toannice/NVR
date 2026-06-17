package vn.aiview.nvr.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime

object Cameras : Table("cameras") {
    val slug = varchar("slug", 255)
    val displayName = varchar("display_name", 255)
    val ip = varchar("ip", 50).nullable()
    val manufacturer = varchar("manufacturer", 100).default("Unknown")
    val model = varchar("model", 100).default("Unknown")
    val onvifPort = integer("onvif_port").default(80)
    val ptzEnabled = bool("ptz_enabled").default(false)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    // Cloud-side fields (003-cloud-data-sync) — unified source of truth.
    val channelId = varchar("channel_id", 50).nullable()
    val username = varchar("username", 255).nullable()
    val passwordEncrypted = text("password_encrypted").nullable()
    val mainToken = varchar("main_token", 255).default("")
    val subToken = varchar("sub_token", 255).default("")
    val rtspUrl = text("rtsp_url").default("")
    val subRtspUrl = text("sub_rtsp_url").nullable()
    val macAddress = varchar("mac_address", 50).nullable()
    val hasPtz = bool("has_ptz").default(false)
    // Manual PTZ capability override for cameras whose ONVIF GetNodes under-reports
    // (e.g. a real pan/tilt cam that only advertises a Zoom velocity space):
    // null/"auto" = trust GetNodes probe, "full" = force Pan+Tilt+Zoom, "zoom" = zoom only.
    val ptzMode = varchar("ptz_mode", 20).nullable()

    // Gateway provisioning (4-6 Plan A) — management metadata only; live ONVIF/RTSP path still
    // reads ip/onvifPort. For a gateway camera: ip = gateway.zerotierIp, onvif_port = assigned ONVIF
    // proxy port, assigned_rtsp_port = assigned RTSP proxy port, local_ip = camera IP on field LAN.
    val gatewayId = varchar("gateway_id", 50).nullable().index()
    val localIp = varchar("local_ip", 50).nullable()
    val assignedRtspPort = integer("assigned_rtsp_port").nullable()

    override val primaryKey = PrimaryKey(slug)
    init { uniqueIndex("cameras_channel_id_uq", channelId) }
}

/**
 * Edge gateways (Raspberry Pi, 4-6 Plan A). Passive L4 proxies on the ZeroTier mesh —
 * the gateway never connects to the NVR; the NVR discovers it (auto-scan ZT subnet) and drives it.
 * No agent token stored here (fleet token is a shared Docker secret); ZeroTier member authorize
 * is done out-of-band in ZeroTier Central.
 */
object Gateways : Table("gateways") {
    val id = varchar("id", 50)
    val name = varchar("name", 255).default("")
    val zerotierIp = varchar("zerotier_ip", 50).nullable()
    val agentPort = integer("agent_port").default(9797)
    val status = varchar("status", 20).default("discovered") // discovered | approved | online | offline
    val fieldSubnet = varchar("field_subnet", 50).nullable()
    val ztAddress = varchar("zt_address", 16).nullable()   // 10-char ZeroTier node address (reported by agent /health)
    val installer = varchar("installer", 255).nullable()   // field-worker label set via the Android onboarding app
    val location = varchar("location", 255).nullable()     // site/location label set via the Android onboarding app
    val certExpiry = varchar("cert_expiry", 40).nullable() // Nebula cert NotAfter (RFC3339) from agent /health
    val lastSeen = datetime("last_seen").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}

object Schedules : Table("schedules") {
    val id = varchar("id", 255)
    val cameraSlug = varchar("camera_slug", 255).index()
    val days = text("days") // Stored as JSON string
    val startTime = varchar("start_time", 10)
    val endTime = varchar("end_time", 10)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}

object LockConfig : Table("lock_config") {
    val id = integer("id").default(1)
    val hash = text("hash")
    val gmailHash = text("gmail_hash").nullable()
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}

object OtpTokens : Table("otp_tokens") {
    val email = varchar("email", 255)
    val code = varchar("code", 6)
    val expiry = long("expiry")

    override val primaryKey = PrimaryKey(email)
}

object CloudConfig : Table("cloud_config") {
    val id = integer("id").default(1)
    val domain = varchar("domain", 255).default("")
    val port = integer("port").default(443)
    val username = varchar("username", 255).default("")
    val passwordEncrypted = text("password_encrypted").default("")
    val serial = varchar("serial", 20).default("")
    val enabled = bool("enabled").default(false)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}

// DEPRECATED: migrated to Cameras in 003-cloud-data-sync. Kept for rollback safety; remove in next release.
object CloudCameras : Table("cloud_cameras") {
    val channelId = varchar("channel_id", 50)
    val name = varchar("name", 255).default("")
    val ip = varchar("ip", 100).default("")
    val port = integer("port").default(80)
    val username = varchar("username", 255).default("")
    val password = varchar("password", 255).default("")
    val mainToken = varchar("main_token", 255).default("")
    val subToken = varchar("sub_token", 255).default("")
    val rtspUrl = text("rtsp_url").default("")
    val subRtspUrl = text("sub_rtsp_url").nullable()
    val macAddress = varchar("mac_address", 50).nullable()
    val hasPtz = bool("has_ptz").default(false)
    override val primaryKey = PrimaryKey(channelId)
}

/**
 * AiView Box integration settings (NVR_AIView_Integration_Protocol v1.1, §8 — auth hướng A).
 * Single-row (id=1). A dedicated username/password pair for the Box↔NVR integration, fully
 * independent of the pattern-lock (LockConfig): locking the NVR UI does NOT disable this API.
 * Admin toggles `enabled` and rotates credentials to grant/revoke the Box.
 */
object IntegrationSettings : Table("integration_settings") {
    val id = integer("id").default(1)                  // single-row, always = 1
    val enabled = bool("enabled").default(false)        // master on/off for the Box integration API
    val username = varchar("username", 255).default("")
    val passwordHash = text("password_hash").default("") // bcrypt (verifyPattern-compatible)
    val passwordLen = integer("password_len").default(0)  // length of the plaintext password — for the masked •••• UI hint only (NOT the secret)
    // Box WebSocket event stream: NVR connects as client to ws://{boxIp}:{boxPort}/ws/events
    val boxIp = varchar("box_ip", 128).default("")
    val boxPort = integer("box_port").default(8088)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}

/**
 * Successful AiView Box connections, keyed by client IP. Lets the NVR operator see which Box(es)
 * have actually authenticated against this NVR. A row is created only after a successful auth on
 * /api/getprofilecamera; the periodic /api/getcamerastatus poll just bumps `lastSeen` of an
 * already-known IP (never creates a row from an unauthenticated call).
 */
object IntegrationConnections : Table("integration_connections") {
    val ip = varchar("ip", 64)
    val username = varchar("username", 255).default("")
    val firstSeen = long("first_seen")                    // epoch millis of first successful auth
    val lastSeen = long("last_seen")                      // epoch millis of most recent activity
    val hitCount = long("hit_count").default(0)           // total recorded requests from this IP
    val lastEndpoint = varchar("last_endpoint", 128).default("")

    override val primaryKey = PrimaryKey(ip)
}

object AIEvents : Table("ai_events") {
    val id = varchar("id", 255)
    val cameraSlug = varchar("camera_slug", 255).index()
    val label = varchar("label", 100)
    val score = double("score").nullable()
    val boxX = double("box_x").nullable()
    val boxY = double("box_y").nullable()
    val boxW = double("box_w").nullable()
    val boxH = double("box_h").nullable()
    val snapshotPath = text("snapshot_path").nullable()
    val startTime = long("start_time").index()
    val endTime = long("end_time").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    // "frigate" or "box" — lets the UI filter/badge events by source
    val eventSource = varchar("source", 20).default("frigate")

    override val primaryKey = PrimaryKey(id)
}
