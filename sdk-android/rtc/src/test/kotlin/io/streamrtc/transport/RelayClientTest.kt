package io.streamrtc.transport

import io.streamrtc.api.RtcLogger
import io.streamrtc.protocol.FrameCodec
import io.streamrtc.session.PeerInfo
import io.streamrtc.session.RelayEvent
import io.streamrtc.session.RelayState
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayClientTest {
    @Test
    fun registersAndEmitsTheSlotAndPeerInfo() = runTest {
        val t = FakeRelayTransport()
        val client = RelayClient({ t }, backgroundScope, RtcLogger.None, Backoff(1, 2, 0.0, Random(1)))
        val events = mutableListOf<RelayEvent>()
        val job = launch { client.events.collect { events.add(it) } }
        client.connect("jwt", "wss://relay")
        t.emitOpen()
        advanceUntilIdle()
        assertEquals("""{"t":"register","token":"jwt"}""", t.sentText.first())
        t.emitText("""{"t":"registered","slot":2,"peers":[0,1],"peerInfo":[{"slot":0,"userId":"a"},{"slot":1,"userId":"b"}],"limits":{"maxParticipants":16,"maxVideoParticipants":8}}""")
        advanceUntilIdle()
        val reg = events.filterIsInstance<RelayEvent.Registered>().single()
        assertEquals(2, reg.slot)
        assertEquals(listOf(0, 1), reg.peers)
        assertEquals(8, reg.limits.maxVideo)
        assertEquals(listOf(PeerInfo(0, "a"), PeerInfo(1, "b")), reg.peerInfo)
        assertEquals(RelayState.Active, client.state.value)
        job.cancel()
    }

    @Test
    fun routesBinaryFramesAndIgnoresMalformedJson() = runTest {
        val t = FakeRelayTransport()
        val client = RelayClient({ t }, backgroundScope, RtcLogger.None, Backoff(1, 2, 0.0, Random(1)))
        val events = mutableListOf<RelayEvent>()
        val job = launch { client.events.collect { events.add(it) } }
        client.connect("jwt", "wss://relay"); t.emitOpen()
        // Let the collector subscribe before the relay starts talking: the
        // events flow has no replay, so earlier emissions would be dropped.
        advanceUntilIdle()
        t.emitText("""{"t":"registered","slot":0,"peers":[],"peerInfo":[],"limits":{"maxParticipants":16,"maxVideoParticipants":8}}""")
        val frame = FrameCodec.encodeRelay(FrameCodec.TYPE_PCM16, 0, 3, 20, byteArrayOf(1, 2, 3))
        t.emitBinary(frame)
        t.emitText("not json")
        advanceUntilIdle()
        assertEquals(3, events.filterIsInstance<RelayEvent.Media>().single().frame.senderSlot)
        job.cancel()
    }

    @Test
    fun sendsKeyframeRequestsToASlot() = runTest {
        val t = FakeRelayTransport()
        val client = RelayClient({ t }, backgroundScope, RtcLogger.None, Backoff(1, 2, 0.0, Random(1)))
        client.connect("jwt", "wss://relay"); t.emitOpen()
        client.requestKeyframe(4)
        assertEquals("""{"t":"keyframe","d":{"slot":4}}""", t.sentText.last())
    }

    @Test
    fun reconnectsOnTransportFaultsAndLeavesTokenRefreshToTheSession() = runTest {
        val t = FakeRelayTransport()
        val client = RelayClient({ t }, backgroundScope, RtcLogger.None, Backoff(1, 2, 0.0, Random(1)))
        val events = mutableListOf<RelayEvent>()
        val job = launch { client.events.collect { events.add(it) } }
        client.connect("jwt", "wss://relay"); t.emitOpen()
        advanceUntilIdle()

        // RECONNECT (1011): the client retries by itself after the backoff delay.
        t.emitClosed(1011, "boom")
        assertEquals(RelayState.Reconnecting(1), client.state.value)
        assertEquals(1, t.connectCount)
        advanceTimeBy(2)
        assertEquals(2, t.connectCount)
        t.emitOpen()
        advanceUntilIdle()
        assertEquals("""{"t":"register","token":"jwt"}""", t.sentText.last())

        // REFRESH_TOKEN (4401): reported, not retried — a fresh token can only
        // come from the session (Task 18), which calls connect() again.
        t.emitText("""{"t":"registered","slot":0,"peers":[],"peerInfo":[],"limits":{"maxParticipants":16,"maxVideoParticipants":8}}""")
        advanceUntilIdle()
        t.emitClosed(4401, "invalid_token")
        advanceUntilIdle()
        assertEquals(RelayState.Reconnecting(1), client.state.value)
        assertEquals(4401, events.filterIsInstance<RelayEvent.Closed>().last().code)
        advanceTimeBy(1_000)
        assertEquals(2, t.connectCount)

        // connect() with the fresh token builds a new transport and re-registers.
        client.connect("jwt2", "wss://relay2")
        t.emitOpen()
        advanceUntilIdle()
        assertEquals(3, t.connectCount)
        assertEquals("""{"t":"register","token":"jwt2"}""", t.sentText.last())
        job.cancel()
    }

    @Test
    fun aTerminalCloseFailsTheLinkAndACloseRaceStaysIdle() = runTest {
        val t = FakeRelayTransport()
        val client = RelayClient({ t }, backgroundScope, RtcLogger.None, Backoff(1, 2, 0.0, Random(1)))
        val events = mutableListOf<RelayEvent>()
        val job = launch { client.events.collect { events.add(it) } }
        client.connect("jwt", "wss://relay"); t.emitOpen()
        advanceUntilIdle()

        // TERMINAL (1008): Failed(code), the close is surfaced, no retry.
        t.emitClosed(1008, "duplicate session")
        advanceUntilIdle()
        assertEquals(RelayState.Failed(1008), client.state.value)
        assertEquals(1008, events.filterIsInstance<RelayEvent.Closed>().single().code)
        advanceTimeBy(1_000)
        assertEquals(1, t.connectCount)
        job.cancel()

        // A close() racing the terminal callback wins: the final state is Idle.
        val t2 = FakeRelayTransport()
        lateinit var racing: RelayClient
        val stoppingLogger = object : RtcLogger {
            override fun d(tag: String, message: String) {
                racing.close()
            }

            override fun e(tag: String, message: String, error: Throwable?) = Unit
        }
        racing = RelayClient({ t2 }, backgroundScope, stoppingLogger, Backoff(1, 2, 0.0, Random(1)))
        racing.connect("jwt", "wss://relay"); t2.emitOpen()
        advanceUntilIdle()
        t2.emitClosed(1008, "duplicate session")
        advanceUntilIdle()
        assertEquals(RelayState.Idle, racing.state.value)
        assertTrue(t2.closed)
    }
}
