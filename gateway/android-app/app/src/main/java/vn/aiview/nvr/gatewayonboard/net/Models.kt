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
    // Nebula agents report these instead of the ZeroTier fields (overlay == "nebula"). `enrolled` = a
    // region (signing URL + token) was set; `signed` = the cert was issued and nebula is up.
    val overlay: String = "",
    val enrolled: Boolean = false,
    val signed: Boolean = false,
    val overlayIp: String = "",
    val lighthouse: String = "",
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

/**
 * Body of POST /enroll — the "mã khu vực" the admin gave the field worker: the HQ signing-service URL
 * and a one-time enrollment token. (Nebula analog of the old 16-hex ZeroTier network id.)
 */
@Serializable
data class EnrollRequest(
    val signingUrl: String,
    val token: String,
    val name: String = "",
)

/**
 * Response of POST /enroll. `status` is normally "pending" right after enrolling — the gateway has
 * submitted its public key but stays unsigned until the admin approves it on the NVR ("Cấp phép").
 */
@Serializable
data class EnrollResponse(
    val status: String = "",
    val overlayIp: String = "",
    val requested: Boolean = false,
    val note: String = "",
)

@Serializable
data class ErrorResponse(val error: String = "")
