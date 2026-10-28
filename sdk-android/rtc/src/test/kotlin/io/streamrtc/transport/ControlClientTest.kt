package io.streamrtc.transport

import io.streamrtc.api.ConnectionState
import io.streamrtc.api.RtcLogger
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControlClientTest {
    @Test
    fun sendsHelloOnOpenAndGoesOnline() = runTest {
        val t = FakeControlTransport()
        val tokens = mutableListOf("t1", "t2")
        val client = ControlClient(
            { t },
            { tokens.removeFirst() },
            backgroundScope,
            "dev-1",
            null,
            RtcLogger.None,
            Backoff(250, 8000, 0.0, Random(1)),
        )
        client.start()
        t.emitOpen()
        advanceUntilIdle()
        assertTrue(t.sent.single().contains("\"t\":\"hello\""))
        t.emitMessage("""{"t":"hello.ok","d":{"userId":"u1","appId":"a1","serverTime":1}}""")
        advanceUntilIdle()
        assertEquals(ConnectionState.Online, client.state.value)
    }

    @Test
    fun refreshesTheTokenOnA4401AndFailsAfterASecondOne() = runTest {
        val t = FakeControlTransport()
        val calls = mutableListOf<Int>()
        var n = 0
        val client = ControlClient(
            { t },
            { calls.add(++n); "tok$n" },
            backgroundScope,
            "dev-1",
            null,
            RtcLogger.None,
            Backoff(1, 2, 0.0, Random(1)),
        )
        client.start()
        t.emitOpen()
        advanceUntilIdle()
        t.emitClosed(4401, "invalid_token")
        advanceUntilIdle()
        t.emitOpen()
        advanceUntilIdle()
        assertEquals(2, calls.size)
        t.emitClosed(4401, "invalid_token")
        advanceUntilIdle()
        assertTrue(client.state.value is ConnectionState.Failed)
        assertEquals("token_rejected", (client.state.value as ConnectionState.Failed).code)
    }

    @Test
    fun reconnectsWithBackoffOnTransportErrors() = runTest {
        val t = FakeControlTransport()
        val client = ControlClient(
            { t },
            { "tok" },
            backgroundScope,
            "dev-1",
            null,
            RtcLogger.None,
            Backoff(10, 20, 0.0, Random(1)),
        )
        client.start()
        t.emitOpen()
        advanceUntilIdle()
        t.emitClosed(1011, "boom")
        advanceUntilIdle()
        assertTrue(client.state.value is ConnectionState.Reconnecting)
        assertEquals(1, t.connectCount)
        advanceTimeBy(9)
        assertEquals(1, t.connectCount)
        advanceTimeBy(2)
        assertEquals(2, t.connectCount)
    }

    @Test
    fun resetsTheTokenRefreshBudgetAfterAHealthyHandshake() = runTest {
        val t = FakeControlTransport()
        var calls = 0
        val client = ControlClient(
            { t },
            { ++calls; "tok$calls" },
            backgroundScope,
            "dev-1",
            null,
            RtcLogger.None,
            Backoff(1, 2, 0.0, Random(1)),
        )
        client.start()
        t.emitOpen()
        advanceUntilIdle()
        t.emitMessage("""{"t":"hello.ok","d":{"userId":"u1","appId":"a1","serverTime":1}}""")
        advanceUntilIdle()
        assertEquals(ConnectionState.Online, client.state.value)

        t.emitClosed(4401, "invalid_token")
        advanceUntilIdle()
        t.emitOpen()
        advanceUntilIdle()
        t.emitMessage("""{"t":"hello.ok","d":{"userId":"u1","appId":"a1","serverTime":2}}""")
        advanceUntilIdle()
        assertEquals(ConnectionState.Online, client.state.value)

        t.emitClosed(4401, "invalid_token")
        advanceUntilIdle()
        assertTrue(client.state.value is ConnectionState.Reconnecting)
        assertEquals(3, calls)
    }

    @Test
    fun stoppingClosesTheTransportWithoutFailing() = runTest {
        val t = FakeControlTransport()
        val client = ControlClient(
            { t },
            { "tok" },
            backgroundScope,
            "dev-1",
            null,
            RtcLogger.None,
            Backoff(10, 20, 0.0, Random(1)),
        )
        client.start()
        client.start()
        t.emitOpen()
        advanceUntilIdle()
        assertEquals(1, t.connectCount)

        client.stop()
        advanceUntilIdle()
        assertTrue(t.closed)
        assertFalse(client.state.value is ConnectionState.Failed)
        assertEquals(ConnectionState.Idle, client.state.value)
    }
}
