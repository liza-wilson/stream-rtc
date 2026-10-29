package io.streamrtc.session

import io.streamrtc.api.ConnectionState
import io.streamrtc.protocol.ServerEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The control-plane link the session manager drives: the send half of
 * [MessageSink] plus the connection state and the server event stream.
 * [ControlClient] is the real implementation.
 */
interface ControlLink : MessageSink {
    val state: StateFlow<ConnectionState>
    val events: SharedFlow<ServerEvent>
    fun start()
    fun stop()
}

/** The media relay link: registration, media frames, and keyframe requests. */
interface RelayLink {
    val state: StateFlow<RelayState>
    val events: SharedFlow<RelayEvent>

    /**
     * Opens a relay connection, registering with [token] against [url]. While
     * a connection is live, a new call supersedes it: the old transport is
     * closed and the new one owns the link. Transport faults are retried by
     * the client with the same credentials; a token rejection (close 4401) is
     * only reported, and the session must fetch a fresh token and call
     * [connect] again.
     */
    fun connect(token: String, url: String)

    /** Sends one binary media frame; false when no socket is connected. */
    fun send(bytes: ByteArray): Boolean

    /** Asks the relay to request a keyframe from [slot]. */
    fun requestKeyframe(slot: Int)

    /** Bytes buffered in the transport's outgoing queue, for quality laddering. */
    fun queuedBytes(): Long

    /** Closes the connection and stops retrying. Never reports a failure after. */
    fun close()
}

/** Lifecycle of the media relay connection. */
sealed interface RelayState {
    /** Not connected, or explicitly closed. */
    data object Idle : RelayState

    /** The socket is open or opening; `register` is in flight. */
    data object Connecting : RelayState

    /** The relay accepted `registered`; media flows. */
    data object Active : RelayState

    /** A reconnect is scheduled or underway. [attempt] starts at 1. */
    data class Reconnecting(val attempt: Int) : RelayState

    /**
     * The relay gave up: a terminal close code such as 4400, 4403, 4404, 4405,
     * 4409, 1008, or 1009 (see [io.streamrtc.transport.actionForCloseCode]).
     */
    data class Failed(val code: Int) : RelayState
}
