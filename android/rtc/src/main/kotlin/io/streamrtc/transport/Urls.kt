package io.streamrtc.transport

/**
 * Maps the host-facing control base URL to the media-relay websocket endpoint
 * (`…/relay`). The combined server routes `/ws` upgrades to the control plane
 * and serves the media relay under `/relay`, so room sockets must target
 * `/relay` explicitly (see `backend/packages/control-plane/src/combined.ts`).
 */
fun relaySocketUrl(base: String): String {
    val socket = when {
        base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
        base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
        else -> base
    }
    val trimmed = socket.trimEnd('/')
    return if (trimmed.endsWith("/relay")) trimmed else "$trimmed/relay"
}
