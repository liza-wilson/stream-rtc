package io.streamrtc.api

/**
 * Why a call stopped, in host-facing terms.
 *
 * [RemoteEnded] and [LocalEnded] distinguish who hung up; [Rejected] covers a
 * callee declining or the server refusing the accept; [NoAnswer] is the server's
 * ringing timeout; [CalleeOffline] arrives synchronously from `call.invite`.
 * [NetworkFailed] and [PermissionDenied] are produced locally when media or the
 * relay fails; [Error] carries a server error code verbatim.
 */
sealed interface EndReason {
    data object RemoteEnded : EndReason
    data object LocalEnded : EndReason
    data object Rejected : EndReason
    data object NoAnswer : EndReason
    data object CalleeOffline : EndReason
    data object NetworkFailed : EndReason
    data object PermissionDenied : EndReason
    data class Error(val code: String) : EndReason
}

/** Lifecycle of a 1:1 call exposed to the host application. */
sealed interface CallState {
    /** Outgoing: the invite is about to be (or has been) sent. */
    data object Dialing : CallState

    /** Incoming: the callee is being prompted. */
    data object Ringing : CallState

    /** Signalling agreed; the relay token and registration are in flight. */
    data object Connecting : CallState

    /** The relay accepted `register`; media can flow. */
    data object Active : CallState

    /** Terminal. Further signalling for this call is ignored. */
    data class Ended(val reason: EndReason) : CallState
}
