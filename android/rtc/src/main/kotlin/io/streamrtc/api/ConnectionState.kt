package io.streamrtc.api

/** Lifecycle of the control-plane connection exposed to the host application. */
sealed interface ConnectionState {
    /** Not started, or explicitly stopped. */
    data object Idle : ConnectionState

    /** The first connection attempt is in progress. */
    data object Connecting : ConnectionState

    /** The control plane accepted `hello`; signalling and presence are live. */
    data object Online : ConnectionState

    /** A reconnect is scheduled or underway. [attempt] starts at 1. */
    data class Reconnecting(val attempt: Int) : ConnectionState

    /**
     * The connection gave up. [code] is a stable, host-facing reason such as
     * `token`, `token_rejected`, `room_unavailable`, `duplicate_session`, or
     * `closed`.
     */
    data class Failed(val code: String) : ConnectionState
}
