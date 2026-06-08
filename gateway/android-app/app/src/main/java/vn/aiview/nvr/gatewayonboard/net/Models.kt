package vn.aiview.nvr.gatewayonboard.net

import kotlinx.serialization.Serializable

/**
 * Response of the edge agent's no-auth `GET /identity` (and the subset returned by `POST /label`).
 * All fields default so a partial/older agent payload still decodes. See edge-agent/main.go.
 */
@Serializable
data class Identity(
    val gatewayId: String = "",
    val name: String = "",
    val installer: String = "",
    val location: String = "",
    // networkId = the ZeroTier network this gateway is currently pointed at (set via POST /enroll).
    // Empty = not enrolled into any region yet. Older agents that predate /enroll omit it (defaults "").
    val networkId: String = "",
    val ztAddress: String = "",
    val ztJoined: Boolean = false,
    val ztOnline: Boolean = false,
    val zerotierIp: String = "",
    val fieldSubnet: String = "",
    val agentPort: Int = 0,
    val alive: Boolean = false,
    val cameraCount: Int = 0,
)

/** A discovered gateway = its identity plus the LAN host we reached it at. */
data class Gateway(
    val host: String,
    val identity: Identity,
)

@Serializable
data class LabelRequest(
    val name: String,
    val installer: String,
    val location: String,
)

/** Body of POST /enroll — the 16-hex ZeroTier network id the admin gave the field worker. */
@Serializable
data class EnrollRequest(
    val networkId: String,
)

/**
 * Response of POST /enroll. `ztJoined` is usually false right after enrolling — the gateway has
 * requested membership but stays unauthorized until the admin approves it on the NVR ("Cấp phép").
 */
@Serializable
data class EnrollResponse(
    val networkId: String = "",
    val ztAddress: String = "",
    val ztJoined: Boolean = false,
    val ztOnline: Boolean = false,
    val zerotierIp: String = "",
    val requested: Boolean = false,
)

@Serializable
data class ErrorResponse(val error: String = "")
