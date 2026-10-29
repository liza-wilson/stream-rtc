package io.streamrtc.session

import io.streamrtc.protocol.RelayFrame

/** A peer already in the room at registration time, from `registered.peerInfo`. */
data class PeerInfo(val slot: Int, val userId: String)

/**
 * Events surfaced by the relay connection. `need_keyframe` carries no slot —
 * it asks the recipient for a keyframe because the relay could not forward
 * one; the outbound direction is [RelayLink.requestKeyframe].
 */
sealed interface RelayEvent {
    /** The relay accepted `register`: our [slot], the other [peers], and [limits]. */
    data class Registered(
        val slot: Int,
        val peers: List<Int>,
        val limits: Limits,
        val peerInfo: List<PeerInfo>,
    ) : RelayEvent

    data class PeerJoined(val slot: Int, val userId: String) : RelayEvent

    data class PeerLeft(val slot: Int) : RelayEvent

    /** The relay needs a keyframe from us (a peer could not decode our stream). */
    data object KeyframeNeeded : RelayEvent

    /** One decoded binary frame relayed from [RelayFrame.senderSlot]. */
    data class Media(val frame: RelayFrame) : RelayEvent

    /** The relay socket closed with [code]; a reconnect or refresh may follow. */
    data class Closed(val code: Int, val reason: String) : RelayEvent
}
