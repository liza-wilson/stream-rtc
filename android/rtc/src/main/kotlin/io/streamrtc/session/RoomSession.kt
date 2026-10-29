package io.streamrtc.session

import io.streamrtc.api.EndReason
import io.streamrtc.api.JoinOptions
import io.streamrtc.api.Participant
import io.streamrtc.api.Room
import io.streamrtc.api.RoomState
import io.streamrtc.api.Rooms
import io.streamrtc.api.SessionAudio
import io.streamrtc.api.StreamRtcConfig
import io.streamrtc.api.RemoteVideo
import io.streamrtc.media.audio.AudioDevice
import io.streamrtc.service.ServiceControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A joined room: owns the relay connection, participant roster and per-slot
 * remote videos; maps relay events to [RoomState].
 */
class RoomSession(
    override val roomId: String,
    private val config: StreamRtcConfig,
    private val relay: RelayLink,
    private val audioDevice: AudioDevice,
    private val service: ServiceControl,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val logger: io.streamrtc.api.RtcLogger = io.streamrtc.api.RtcLogger.None,
) : Room {

    private val registry = ParticipantRegistry()
    private val _state = MutableStateFlow<RoomState>(RoomState.Connecting)
    override val state: StateFlow<RoomState> = _state.asStateFlow()

    private val _participants = MutableStateFlow<List<Participant>>(emptyList())
    override val participants: StateFlow<List<Participant>> = _participants.asStateFlow()

    private val remoteVideos = mutableMapOf<Int, RemoteVideoImpl>()

    private var selfSlot: Int? = null
    private var collectorJob: Job? = null

    override val self: Participant
        get() = registry.get(selfSlot ?: -1) ?: Participant(slot = selfSlot ?: -1, userId = config.displayName)

    override val audio: SessionAudio = SessionAudioImpl(audioDevice)

    suspend fun join(token: String) {
        relay.connect(token, config.controlUrl)
        collectorJob = scope.launch(Dispatchers.Unconfined) {
            relay.events.collect { event ->
                when (event) {
                    is RelayEvent.Registered -> {
                        selfSlot = event.slot
                        registry.onRegistered(
                            Participant(slot = event.slot, userId = config.displayName),
                            event.peerInfo.map { it.slot to it.userId },
                        )
                        for (slot in event.peers) {
                            if (registry.get(slot) == null) registry.onPeerJoined(slot, "")
                        }
                        _participants.value = registry.snapshot()
                        // create remote videos for peer slots
                        val peerSlots = (event.peers + event.peerInfo.map { it.slot }).distinct()
                        for (slot in peerSlots) {
                            if (slot == event.slot) continue
                            if (remoteVideos[slot] == null) {
                                val userId = event.peerInfo.find { it.slot == slot }?.userId
                                remoteVideos[slot] = RemoteVideoImpl(slot, userId, logger = logger)
                            }
                        }
                        _state.value = RoomState.Active
                    }

                    is RelayEvent.PeerJoined -> {
                        registry.onPeerJoined(event.slot, event.userId)
                        _participants.value = registry.snapshot()
                        if (remoteVideos[event.slot] == null) {
                            remoteVideos[event.slot] = RemoteVideoImpl(event.slot, event.userId, logger = logger)
                        }
                    }

                    is RelayEvent.PeerLeft -> {
                        registry.onPeerLeft(event.slot)
                        remoteVideos.remove(event.slot)?.detach()
                        _participants.value = registry.snapshot()
                    }

                    is RelayEvent.Closed -> {
                        _state.value = RoomState.Closed(EndReason.RemoteEnded)
                    }

                    else -> Unit
                }
            }
        }
    }

    override fun remoteVideo(slot: Int): RemoteVideo? = remoteVideos[slot]

    override suspend fun leave() {
        collectorJob?.cancel()
        collectorJob = null
        remoteVideos.values.forEach { it.detach() }
        remoteVideos.clear()
        relay.close()
        service.stop()
        _state.value = RoomState.Closed(EndReason.LocalEnded)
    }
}
