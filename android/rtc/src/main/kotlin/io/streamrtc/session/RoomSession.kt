package io.streamrtc.session

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import io.streamrtc.api.EndReason
import io.streamrtc.api.LocalVideo
import io.streamrtc.api.Participant
import io.streamrtc.api.RemoteVideo
import io.streamrtc.api.Room
import io.streamrtc.api.RoomState
import io.streamrtc.api.SessionAudio
import io.streamrtc.api.StreamRtcConfig
import io.streamrtc.media.audio.AudioDevice
import io.streamrtc.media.video.CameraController
import io.streamrtc.media.video.VideoEncoder
import io.streamrtc.protocol.FrameCodec
import io.streamrtc.service.ServiceControl
import io.streamrtc.transport.relaySocketUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The sender-side half of keyframe flow control: the pump stands in for the
 * H.264 encoder until video starts (JVM-session tests inject a recorder).
 */
fun interface KeyframePump {
    fun requestKeyframe()
}

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
    roomKeyframePump: KeyframePump? = null,
) : Room {

    private val registry = ParticipantRegistry()
    private val _state = MutableStateFlow<RoomState>(RoomState.Connecting)
    override val state: StateFlow<RoomState> = _state.asStateFlow()

    private val _participants = MutableStateFlow<List<Participant>>(emptyList())
    override val participants: StateFlow<List<Participant>> = _participants.asStateFlow()

    private val remoteVideosMap = mutableMapOf<Int, RemoteVideoImpl>()
    private val _remoteVideos = MutableStateFlow<Map<Int, RemoteVideo>>(emptyMap())
    override val remoteVideos: StateFlow<Map<Int, RemoteVideo>> = _remoteVideos.asStateFlow()

    private val _localVideo = MutableStateFlow<LocalVideo?>(null)
    override val localVideo: StateFlow<LocalVideo?> = _localVideo.asStateFlow()

    private var selfSlot: Int? = null
    private var collectorJob: Job? = null
    private var encoder: VideoEncoder? = null
    private var cameraController: CameraController? = null
    private var keyframePump: KeyframePump = roomKeyframePump ?: KeyframePump {}

    override val self: Participant
        get() = registry.get(selfSlot ?: -1) ?: Participant(slot = selfSlot ?: -1, userId = config.displayName)

    override val audio: SessionAudio = SessionAudioImpl(audioDevice)

    /**
     * Joins via the media relay: [relayUrl] must be the relay websocket
     * endpoint (usually [io.streamrtc.transport.relaySocketUrl] of the
     * control base URL), not the control plane itself.
     */
    suspend fun join(token: String, relayUrl: String) {
        relay.connect(token, relayUrl)
        service.start()
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

                        val peerSlots = (event.peers + event.peerInfo.map { it.slot }).distinct()
                        for (slot in peerSlots) {
                            if (slot == event.slot) continue
                            if (remoteVideosMap[slot] == null) {
                                val userId = event.peerInfo.find { it.slot == slot }?.userId
                                remoteVideosMap[slot] = RemoteVideoImpl(
                                    slot = slot,
                                    userId = userId,
                                    onNeedKeyframe = { relay.requestKeyframe(slot) },
                                    logger = logger,
                                )
                            }
                        }
                        _remoteVideos.value = remoteVideosMap.toMap()

                        audioDevice.startCapture { bytes, mediaTs ->
                            if (!audio.muted) {
                                val frame = FrameCodec.encodeClient(FrameCodec.TYPE_PCM16, 0, mediaTs, bytes)
                                relay.send(frame)
                            }
                        }
                        _state.value = RoomState.Active
                    }

                    is RelayEvent.PeerJoined -> {
                        registry.onPeerJoined(event.slot, event.userId)
                        _participants.value = registry.snapshot()
                        if (remoteVideosMap[event.slot] == null) {
                            remoteVideosMap[event.slot] = RemoteVideoImpl(
                                slot = event.slot,
                                userId = event.userId,
                                onNeedKeyframe = { relay.requestKeyframe(event.slot) },
                                logger = logger,
                            )
                        }
                        _remoteVideos.value = remoteVideosMap.toMap()
                    }

                    is RelayEvent.PeerLeft -> {
                        registry.onPeerLeft(event.slot)
                        remoteVideosMap.remove(event.slot)?.detach()
                        _remoteVideos.value = remoteVideosMap.toMap()
                        _participants.value = registry.snapshot()
                        audioDevice.removeSlot(event.slot)
                    }

                    is RelayEvent.Media -> {
                        val frame = event.frame
                        if (frame.senderSlot != selfSlot) {
                            if (frame.type == FrameCodec.TYPE_PCM16) {
                                audioDevice.write(frame.senderSlot, frame.payload)
                                remoteVideosMap[frame.senderSlot]?.onAudioFrame(frame.mediaTs, System.currentTimeMillis())
                            } else if (frame.type == FrameCodec.TYPE_H264) {
                                remoteVideosMap[frame.senderSlot]?.push(frame)
                            }
                        }
                    }

                    is RelayEvent.Closed -> {
                        _state.value = RoomState.Closed(EndReason.RemoteEnded)
                    }

                    is RelayEvent.KeyframeNeeded -> keyframePump.requestKeyframe()
                }
            }
        }
    }

    override fun remoteVideo(slot: Int): RemoteVideo? = remoteVideosMap[slot]

    override fun startVideo(context: Context, lifecycleOwner: LifecycleOwner) {
        if (encoder != null && cameraController != null) {
            cameraController?.setEnabled(true)
            return
        }
        val enc = VideoEncoder(
            config = config.video,
            onFrame = { bytes, isKey, ts ->
                val flags = if (isKey) FrameCodec.FLAG_KEYFRAME else 0
                val frame = FrameCodec.encodeClient(FrameCodec.TYPE_H264, flags, ts, bytes)
                relay.send(frame)
            },
            logger = logger,
        )
        enc.start()
        this.encoder = enc

        val surface = enc.surface
        if (surface != null) {
            val cam = CameraController(context, lifecycleOwner) { err ->
                logger.e("RoomSession", "Camera capture error", err)
            }
            cam.bindTo(surface)
            this.cameraController = cam
            _localVideo.value = cam
        }
        keyframePump = KeyframePump { enc.requestKeyframe() }
    }

    override fun stopVideo() {
        cameraController?.setEnabled(false)
        cameraController?.unbind()
        cameraController = null
        _localVideo.value = null
        encoder?.stop()
        encoder = null
        keyframePump = KeyframePump {}
    }

    override suspend fun leave() {
        collectorJob?.cancel()
        collectorJob = null
        stopVideo()
        audioDevice.stopCapture()
        remoteVideosMap.values.forEach { it.detach() }
        remoteVideosMap.clear()
        _remoteVideos.value = emptyMap()
        relay.close()
        service.stop()
        _state.value = RoomState.Closed(EndReason.LocalEnded)
    }
}
