package io.streamrtc.api

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The 1:1 calling surface. Obtain it from [io.streamrtc.StreamRtc.calls];
 * [io.streamrtc.StreamRtc.start] must have been called before dialing.
 */
interface Calls {
    /**
     * Incoming invites. Each emission is already ringing on the server and must
     * be answered with [IncomingCall.accept] or [IncomingCall.reject]; nothing
     * is auto-answered.
     */
    val incoming: SharedFlow<IncomingCall>

    /** The live call, or null when none is dialing, ringing, or connected. */
    val active: StateFlow<Call?>

    /** Invites [userId]. Throws if a call is already in progress on this device. */
    suspend fun dial(userId: String, media: CallMedia = CallMedia.audioVideo): Call
}

/**
 * One ringing incoming invite. It is only a handle: the SDK does not answer it
 * until [accept] is called, and the server reaps it after the ring timeout.
 */
class IncomingCall internal constructor(
    val callId: String,
    val fromUserId: String,
    val displayName: String?,
    val roomId: String,
    val media: CallMedia,
    private val acceptFn: suspend () -> Call,
    private val rejectFn: suspend (String?) -> Unit,
) {
    /** Accepts the invite and returns the now-connecting call. */
    suspend fun accept(): Call = acceptFn()

    /** Declines the invite with an optional host-visible [reason]. */
    suspend fun reject(reason: String? = null) = rejectFn(reason)
}

/** One 1:1 call. State is conflated; collect it from any thread. */
interface Call {
    /**
     * The server's call id once it is known. Before the server names an
     * outgoing call this is a locally generated id, so it is never empty.
     */
    val id: String

    /** The other party's user id. */
    val peerUserId: String

    val state: StateFlow<CallState>

    /** Mute and route controls for this call. */
    val audio: SessionAudio

    /** Hangs up. Idempotent: ending an ended call does nothing. */
    suspend fun end(reason: String? = null)
}

/**
 * Audio controls shared by calls and rooms. All members are safe to touch from
 * the main thread and never block.
 */
interface SessionAudio {
    /** Locally mutes the microphone; captured frames are dropped, not sent. */
    var muted: Boolean

    /** Routes playback to the loudspeaker when true, the earpiece when false. */
    var speakerphone: Boolean

    /** The route the platform is currently using, as far as the SDK knows. */
    val route: StateFlow<AudioRoute>
}

/** Where call audio is going. */
enum class AudioRoute { Earpiece, Speaker, Headset, Bluetooth }
