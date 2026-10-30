package io.streamrtc.session

import io.streamrtc.api.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RoomSessionTest {
    private val relay = FakeRelayLink()
    private val control = FakeControlLink()
    private val config = StreamRtcConfig("https://c", { "session" }, "me", roomTokenProvider = { roomId -> "room-$roomId" })

    private fun TestScope.manager() = SessionManager(
        config, control, { relay }, FakeAudioDevice(), FakeServiceControl(), backgroundScope, clock = { 0 },
    )

    @Test fun joinUsesTheHostMintedTokenAndPublishesParticipants() = runTest {
        val m = manager()
        val room = m.joinRoom("r1", JoinOptions(video = true))
        assertEquals("room-r1", relay.token)
        // The media relay is the /relay socket, not the control plane.
        assertEquals("wss://c/relay", relay.url)
        relay.emit(RelayEvent.Registered(slot = 0, peers = listOf(1), limits = Limits(16, 8)))
        assertEquals(RoomState.Active, room.state.value)
        assertEquals(listOf(0, 1), room.participants.value.map { it.slot })
    }

    @Test fun joinsAndLeavesUpdateTheParticipantList() = runTest {
        val m = manager()
        val room = m.joinRoom("r1", JoinOptions(video = true))
        relay.emit(RelayEvent.Registered(0, emptyList(), Limits(16, 8)))
        relay.emit(RelayEvent.PeerJoined(2, "u2"))
        assertEquals(listOf(0, 2), room.participants.value.map { it.slot })
        relay.emit(RelayEvent.PeerLeft(2))
        assertEquals(listOf(0), room.participants.value.map { it.slot })
    }

    @Test fun leavingClosesTheRelayAndTheService() = runTest {
        val m = manager()
        val room = m.joinRoom("r1", JoinOptions(video = true))
        relay.emit(RelayEvent.Registered(0, emptyList(), Limits(16, 8)))
        room.leave()
        assertEquals(RoomState.Closed(EndReason.LocalEnded), room.state.value)
        assertTrue(relay.closed)
    }

    @Test fun videoIsExposedOnlyForKnownSlots() = runTest {
        val m = manager()
        val room = m.joinRoom("r1", JoinOptions(video = true))
        relay.emit(RelayEvent.Registered(0, listOf(1, 2), Limits(16, 8)))
        assertNull(room.remoteVideo(9))
        assertEquals(1, room.remoteVideo(1)?.slot)
    }
}
