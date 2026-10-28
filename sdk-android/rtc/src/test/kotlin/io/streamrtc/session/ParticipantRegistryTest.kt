package io.streamrtc.session

import io.streamrtc.api.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParticipantRegistryTest {
    private val self = Participant(slot = 0, userId = "me", hasVideo = true)

    @Test
    fun mergesRegisteredPeersAndJoins() {
        val r = ParticipantRegistry()
        r.onRegistered(self, listOf(1 to "a", 2 to "b"))
        r.onPeerJoined(3, "c")
        assertEquals(listOf(1, 2, 3), r.snapshot().filter { it.slot != 0 }.map { it.slot })
        assertEquals("b", r.get(2)?.userId)
    }

    @Test
    fun removesLeaversAndReplacesAReusedSlot() {
        val r = ParticipantRegistry()
        r.onRegistered(self, listOf(1 to "a"))
        r.onPeerLeft(1)
        assertNull(r.get(1))
        r.onPeerJoined(1, "someone-else")
        assertEquals("someone-else", r.get(1)?.userId)
    }

    @Test
    fun tracksVideoIntentDiscoveredFromFrames() {
        val r = ParticipantRegistry()
        r.onRegistered(self, listOf(1 to "a"))
        assertEquals(false, r.get(1)?.hasVideo)
        r.setHasVideo(1, true)
        assertEquals(true, r.get(1)?.hasVideo)
    }
}
