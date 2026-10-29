package io.streamrtc.session

import io.streamrtc.api.Participant

/**
 * Who is in the room, keyed by relay slot.
 *
 * Fed from relay events on the session's single-threaded executor, so it is not
 * synchronised: callers must not touch it from several threads at once.
 */
class ParticipantRegistry {
    private val participants = LinkedHashMap<Int, Participant>()
    private var selfSlot: Int? = null

    /** Rebuilds the roster from `registered`: self plus the peers already in the room. */
    fun onRegistered(self: Participant, peerInfo: List<Pair<Int, String>>) {
        selfSlot = self.slot
        participants.clear()
        participants[self.slot] = self
        for ((slot, userId) in peerInfo) {
            if (slot == self.slot) continue
            participants[slot] = Participant(slot = slot, userId = userId)
        }
    }

    /** A slot was taken (or a stale occupant replaced); video intent is unknown again. */
    fun onPeerJoined(slot: Int, userId: String) {
        participants[slot] = Participant(slot = slot, userId = userId)
    }

    fun onPeerLeft(slot: Int) {
        participants.remove(slot)
    }

    /** Video intent is learned from the first frame a slot sends. */
    fun setHasVideo(slot: Int, hasVideo: Boolean) {
        val current = participants[slot] ?: return
        participants[slot] = current.copy(hasVideo = hasVideo)
    }

    fun get(slot: Int): Participant? = participants[slot]

    /** Self first, then every other occupant by ascending slot. */
    fun snapshot(): List<Participant> {
        val selfEntry = selfSlot?.let { participants[it] }
        val others = participants.values
            .filter { it.slot != selfEntry?.slot }
            .sortedBy { it.slot }
        return if (selfEntry == null) others else listOf(selfEntry) + others
    }
}
