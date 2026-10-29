package io.streamrtc.transport

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** A WebSocket to the media relay: JSON control text plus binary media frames. */
interface RelayTransport {
    fun connect(
        onOpen: () -> Unit,
        onText: (String) -> Unit,
        onBinary: (ByteArray) -> Unit,
        onClosed: (code: Int, reason: String) -> Unit,
    )

    fun send(text: String): Boolean

    fun send(bytes: ByteArray): Boolean

    fun queuedBytes(): Long

    fun close()
}

/** [RelayTransport] backed by an OkHttp WebSocket. */
class OkHttpRelayTransport(
    private val http: OkHttpClient,
    private val url: String,
) : RelayTransport {
    private val lock = Any()
    private var webSocket: WebSocket? = null

    override fun connect(
        onOpen: () -> Unit,
        onText: (String) -> Unit,
        onBinary: (ByteArray) -> Unit,
        onClosed: (code: Int, reason: String) -> Unit,
    ) {
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // The media path: one wire-to-payload copy, nothing else.
                onBinary(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onClosed(ABNORMAL_CLOSURE, t.message ?: "websocket failure")
            }
        }
        val socket = http.newWebSocket(request, listener)
        val previous = synchronized(lock) {
            val current = webSocket
            webSocket = socket
            current
        }
        previous?.close(NORMAL_CLOSURE, null)
    }

    override fun send(text: String): Boolean {
        val socket = synchronized(lock) { webSocket } ?: return false
        return socket.send(text)
    }

    override fun send(bytes: ByteArray): Boolean {
        val socket = synchronized(lock) { webSocket } ?: return false
        return socket.send(bytes.toByteString())
    }

    override fun queuedBytes(): Long {
        val socket = synchronized(lock) { webSocket } ?: return 0L
        return socket.queueSize()
    }

    override fun close() {
        val socket = synchronized(lock) {
            val current = webSocket
            webSocket = null
            current
        }
        socket?.close(NORMAL_CLOSURE, null)
    }

    private companion object {
        /** Sent when the client closes the socket itself. */
        const val NORMAL_CLOSURE = 1000

        /** OkHttp reports a failed socket as an abnormal closure. */
        const val ABNORMAL_CLOSURE = 1006
    }
}
