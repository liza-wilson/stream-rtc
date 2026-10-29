package io.streamrtc.session

import io.streamrtc.api.CallMedia
import io.streamrtc.api.CallState
import io.streamrtc.api.Limits
import io.streamrtc.api.StreamRtcConfig
import io.streamrtc.protocol.FrameCodec
import io.streamrtc.protocol.Messages
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    private val control = FakeControlLink()
    private val relay = FakeRelayLink()
    private val audio = FakeAudioDevice()
    private val service = FakeServiceControl()
    private var now = 0L

    private fun TestScope.manager() = SessionManager(
        config = StreamRtcConfig("https://c", { "tok" }, "me"),
        control = control,
        relayFactory = { relay },
        audio = audio,
        service = service,
        scope = backgroundScope,
        clock = { now },
    )

    @Test fun dialSendsInviteAndConnectsTheRelayOnceTheTokenArrives() = runTest {
        val m = manager()
        m.start()
        val call = m.dial("peer", CallMedia.audioVideo)
        assertEquals(CallState.Dialing, call.state.value)
        assertTrue(control.sent.single().contains("call.invite"))

        control.emit(Messages.parseServer("""{"t":"call.accepted","d":{"callId":"c1","byUserId":"peer","roomId":"r1"}}""")!!)
        control.emit(tokenEvent())
        assertEquals("jwt", relay.token)
        assertEquals("wss://r", relay.url)
    }

    @Test fun registrationStartsTheServiceAndCaptureAndCapturedAudioIsSent() = runTest {
        val m = manager()
        m.start()
        m.dial("peer", CallMedia.audioVideo)
        control.emit(tokenEvent())
        relay.emit(RelayEvent.Registered(slot = 0, peers = emptyList(), limits = Limits(16, 8)))
        assertEquals(1, service.starts)
        assertTrue(audio.capturing)

        val payload = ByteArray(640)
        audio.emitFrame(payload, 1_000)
        val sent = relay.sentBinary.single()
        val frame = FrameCodec.decodeClient(sent)!!
        assertEquals(FrameCodec.TYPE_PCM16, frame.type)
        assertArrayEquals(payload, frame.payload)
    }

    @Test fun remoteAudioIsWrittenToTheRightSlotAndPeersAreRemoved() = runTest {
        val m = manager()
        m.start()
        relay.emit(RelayEvent.Registered(0, emptyList(), Limits(16, 8)))
        relay.emit(
            RelayEvent.Media(
                FrameCodec.decodeRelay(
                    FrameCodec.encodeRelay(FrameCodec.TYPE_PCM16, 0, 3, 20, ByteArray(640)),
                )!!,
            ),
        )
        assertEquals(listOf(3), audio.written.keys.toList())
        relay.emit(RelayEvent.PeerLeft(3))
        assertEquals(listOf(3), audio.removed)
    }

    @Test fun endingTheCallStopsEverythingInOrder() = runTest {
        val m = manager()
        m.start()
        m.dial("peer", CallMedia.audioVideo)
        control.emit(tokenEvent())
        relay.emit(RelayEvent.Registered(0, emptyList(), Limits(16, 8)))
        m.endAll()
        assertEquals(1, service.stops)
        assertFalse(audio.capturing)
        assertTrue(relay.closed)
    }

    private fun tokenEvent() = Messages.parseServer(
        """{"t":"room.token","d":{"token":"jwt","relayUrl":"wss://r","roomId":"r1","role":"host","expiresIn":300,"peers":[]}}""",
    )!!
}
