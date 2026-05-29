package vn.aiview.nvr.services

import kotlinx.coroutines.sync.Mutex

/**
 * Shared mutex serializing all read-modify-write operations on config/config.yml
 * and the unified Cameras table. Used by both the local Web UI camera routes and
 * the cloud command handlers so concurrent CRUD from either origin cannot race.
 */
object CameraConfigMutex {
    val mutex = Mutex()
}
