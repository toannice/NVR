package vn.aiview.nvr.plugins

import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import vn.aiview.nvr.models.*

fun Application.configureDatabases() {
    val dataDir = File("data")
    if (!dataDir.exists()) {
        dataDir.mkdirs()
    }
    
    // SQLite with WAL mode and configurations
    val url = "jdbc:sqlite:data/nvr.db?journal_mode=WAL&synchronous=1&cache_size=-16000&foreign_keys=on"
    val database = Database.connect(url, driver = "org.sqlite.JDBC")
    
    transaction(database) {
        // Auto-create/update tables
        SchemaUtils.createMissingTablesAndColumns(
            Cameras,
            Schedules,
            LockConfig,
            AIEvents
        )
    }
}
