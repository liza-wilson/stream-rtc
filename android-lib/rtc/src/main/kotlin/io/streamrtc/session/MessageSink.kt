package io.streamrtc.session

/**
 * The send half of a control-plane link: one already-encoded text message.
 * Returns false when there is no live socket, so callers never block on a
 * reconnect.
 */
interface MessageSink {
    fun send(text: String): Boolean
}
