package io.streamrtc.api

import kotlinx.coroutines.flow.StateFlow

/**
 * The group-room surface. Implemented in Task 17; this task defines the types so
 * integrators can compile against the final shape.
 */
interface Rooms {
    /** Joins [roomId] with a room token minted by `StreamRtcConfig.roomTokenProvider`. */
    suspend fun join(roomId: String, options: JoinOptions = JoinOptions()): Room
}

/** Per-join options. [displayName] overrides the SDK-configured name when set. */
data class JoinOptions(val video: Boolean = true, val displayName: String? = null)

/** One joined room. */
interface Room {
    val roomId: String
    val self: Participant
    val state: StateFlow<RoomState>
    val participants: StateFlow<List<Participant>>
    val audio: SessionAudio
    val localVideo: StateFlow<LocalVideo?>
    val remoteVideos: StateFlow<Map<Int, RemoteVideo>>

    /** The remote video published on [slot], or null for an unknown slot. */
    fun remoteVideo(slot: Int): RemoteVideo?

    /** Starts local camera and H.264 encoder. */
    fun startVideo(context: android.content.Context, lifecycleOwner: androidx.lifecycle.LifecycleOwner)

    /** Stops local camera and H.264 encoder. */
    fun stopVideo()

    /** Leaves the room and releases its media. Idempotent. */
    suspend fun leave()
}

/** Lifecycle of a room connection. */
sealed interface RoomState {
    data object Connecting : RoomState
    data object Active : RoomState
    data class Reconnecting(val attempt: Int) : RoomState
    data class Closed(val reason: EndReason) : RoomState
}
