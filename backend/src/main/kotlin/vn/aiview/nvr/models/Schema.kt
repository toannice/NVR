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

    override val primaryKey = PrimaryKey(id)
}
