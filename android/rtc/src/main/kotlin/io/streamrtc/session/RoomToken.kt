package io.streamrtc.session

/**
 * The room credentials minted by the control plane for one participant.
 *
 * [expiresIn] is seconds from receipt; [role] is the server's string, `"host"`
 * for the caller and `"member"` for the callee. The relay URL is a WebSocket
 * URL and the token is opaque to the SDK.
 */
data class RoomToken(
    val token: String,
    val relayUrl: String,
    val roomId: String,
    val role: String,
    val expiresIn: Int,
)
