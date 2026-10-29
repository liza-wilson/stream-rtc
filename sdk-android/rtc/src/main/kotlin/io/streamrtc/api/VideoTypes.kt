package io.streamrtc.api

import kotlinx.coroutines.flow.StateFlow

/** Counters for one video direction. Zero until the pipeline is running. */
data class VideoStats(
    val framesSent: Long = 0,
    val framesReceived: Long = 0,
    val dropped: Long = 0,
    val bitrate: Int = 0,
)

/** Room capacity as reported by the relay in `registered.limits`. */
data class Limits(val maxParticipants: Int, val maxVideo: Int)

/** Lifecycle of one remote video surface. */
enum class RemoteVideoState { Idle, WaitingForKeyframe, Playing, Stalled }

/** One remote participant's video, attached to a view by the host. */
interface RemoteVideo {
    val slot: Int
    val userId: String?
    val state: StateFlow<RemoteVideoState>
    val stats: StateFlow<VideoStats>
}

/** The local camera pipeline. */
interface LocalVideo {
    fun setEnabled(on: Boolean)
    fun switchCamera()
    val stats: StateFlow<VideoStats>
}
