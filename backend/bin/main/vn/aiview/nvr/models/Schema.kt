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

    override val primaryKey = PrimaryKey(slug)
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
