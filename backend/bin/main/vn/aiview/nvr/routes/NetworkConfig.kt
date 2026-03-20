package vn.aiview.nvr.routes

import kotlinx.serialization.Serializable

@Serializable
data class NetworkConfig(
    val mac: String,
    val ip: String,
    val netmask: String,
    val gateway: String,
    val dns: List<String>
)
