package io.streamrtc.transport

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** A text WebSocket to the control plane. */
interface ControlTransport {
    fun connect(
        onOpen: () -> Unit,
        onMessage: (String) -> Unit,
        onClosed: (code: Int, reason: String) -> Unit,
    )

    fun send(text: String): Boolean

    fun close()
}

/** [ControlTransport] backed by an OkHttp WebSocket. */
class OkHttpControlTransport(
    private val http: OkHttpClient,
    private val url: String,
) : ControlTransport {
    private val lock = Any()
    private var webSocket: WebSocket? = null

    override fun connect(
        onOpen: () -> Unit,
        onMessage: (String) -> Unit,
        onClosed: (code: Int, reason: String) -> Unit,
    ) {
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onMessage(text)
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
        synchronized(lock) { webSocket = socket }
    }

    override fun send(text: String): Boolean {
        val socket = synchronized(lock) { webSocket } ?: return false
        return socket.send(text)
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
