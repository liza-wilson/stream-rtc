package io.streamrtc.session

import io.streamrtc.api.CallMedia
import io.streamrtc.api.CallState
import io.streamrtc.api.Limits
import io.streamrtc.api.StreamRtcConfig
import io.streamrtc.protocol.FrameCodec
import io.streamrtc.protocol.Messages
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectTest {
    private fun TestScope.managerWith(
        config: StreamRtcConfig,
        control: FakeControlLink,
        relayFactory: () -> FakeRelayLink,
        onQualityChange: (io.streamrtc.api.VideoConfig) -> Unit = {},
    ): SessionManager = SessionManager(
        config = config,
        control = control,
        relayFactory = { relayFactory() },
        audio = FakeAudioDevice(),
        service = FakeServiceControl(),
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
        onQualityChange = onQualityChange,
    )

    private fun tokenEvent(token: String) = Messages.parseServer(
        """{"t":"room.token","d":{"token":"$token","relayUrl":"wss://r","roomId":"r1","role":"host","expiresIn":300,"peers":[]}}""",
    )!!

    @Test fun a4401FetchesANewTokenThroughCallTokenAndReconnects() = runTest {
        val control = FakeControlLink()
        val relays = mutableListOf<FakeRelayLink>()
        val m = SessionManager(
            StreamRtcConfig("https://c", { "tok" }, "me"), control,
            { FakeRelayLink().also(relays::add) }, FakeAudioDevice(), FakeServiceControl(),
            backgroundScope, clock = { 0 },
        )
        m.start()
        m.dial("peer", CallMedia.audioVideo)
        control.emit(Messages.parseServer("""{"t":"call.accepted","d":{"callId":"c1","byUserId":"peer","roomId":"r1"}}""")!!)
        control.emit(tokenEvent("t1"))
        assertEquals("t1", relays[0].token)

        relays[0].emitClosed(4401, "replayed_token")
        advanceUntilIdle()
        assertTrue(control.sent.any { it.contains("\"t\":\"call.token\"") })

        control.emit(tokenEvent("t2"))
        advanceUntilIdle()
        assertEquals("t2", relays.last().token)
        assertEquals(2, relays.size)
    }

    @Test fun aSlowConsumerStepsTheEncoderDown() = runTest {
        val relay = FakeRelayLink()
        val encoderSteps = mutableListOf<io.streamrtc.api.VideoConfig>()
        val m = SessionManager(
            StreamRtcConfig("https://c", { "tok" }, "me", video = io.streamrtc.api.VideoConfig.Standard),
            FakeControlLink(), { relay }, FakeAudioDevice(), FakeServiceControl(), backgroundScope,
            clock = { testScheduler.currentTime }, onQualityChange = { encoderSteps.add(it) },
        )
        m.start(); m.dial("peer", CallMedia.audioVideo)
        relay.emit(RelayEvent.Registered(0, emptyList(), Limits(16, 8)))
        relay.queued = 80_000
        advanceTimeBy(2_100)
        assertEquals(io.streamrtc.api.VideoConfig.Low, encoderSteps.last())
    }
}
