package io.streamrtc.transport

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpControlTransportTest {
    @Test
    fun carriesTextBothWays() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send("echo:$text")
                    }
                },
            ),
        )
        server.start()
        val transport = OkHttpControlTransport(
            OkHttpClient(),
            server.url("/ws").toString().replace("http", "ws"),
        )
        val messages = LinkedBlockingQueue<String>()
        val opened = CountDownLatch(1)
        transport.connect({ opened.countDown() }, { messages.put(it) }, { _, _ -> })
        assertTrue(opened.await(2, TimeUnit.SECONDS))
        transport.send("hi")
        assertEquals("echo:hi", messages.poll(2, TimeUnit.SECONDS))
        transport.close()
        server.shutdown()
    }

    @Test
    fun connectingAgainClosesThePreviousSocket() {
        val server = MockWebServer()
        val firstClosed = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        firstClosed.countDown()
                        webSocket.close(1000, null)
                    }
                },
            ),
        )
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }
                },
            ),
        )
        server.start()
        val transport = OkHttpControlTransport(
            OkHttpClient(),
            server.url("/ws").toString().replace("http", "ws"),
        )

        val firstOpened = CountDownLatch(1)
        transport.connect({ firstOpened.countDown() }, { _ -> }, { _, _ -> })
        assertTrue(firstOpened.await(2, TimeUnit.SECONDS))

        val secondOpened = CountDownLatch(1)
        transport.connect({ secondOpened.countDown() }, { _ -> }, { _, _ -> })
        assertTrue(secondOpened.await(2, TimeUnit.SECONDS))
        assertTrue(firstClosed.await(2, TimeUnit.SECONDS))

        transport.close()
        server.shutdown()
    }
}
