package io.streamrtc.session

import io.streamrtc.api.RemoteVideo
import io.streamrtc.api.RemoteVideoState
import io.streamrtc.api.RtcLogger
import io.streamrtc.api.VideoStats
import io.streamrtc.media.FrameGate
import io.streamrtc.media.SyncEstimator
import io.streamrtc.media.VideoAction
import io.streamrtc.media.VideoScheduler
import io.streamrtc.media.video.VideoDecoder
import io.streamrtc.view.SurfaceSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-slot remote video. Lazily builds a [VideoDecoder] on [attach] and tears it
 * down on [detach]; [state]/[stats] are exposed as [StateFlow]s.
 */
class RemoteVideoImpl(
    override val slot: Int,
    override val userId: String?,
    private val logger: io.streamrtc.api.RtcLogger = io.streamrtc.api.RtcLogger.None,
) : RemoteVideo, SurfaceSink {

    private val _state = MutableStateFlow(RemoteVideoState.Idle)
    override val state: StateFlow<RemoteVideoState> = _state

    private val _stats = MutableStateFlow(VideoStats())
    override val stats: StateFlow<VideoStats> = _stats

    private var decoder: VideoDecoder? = null

    val gate = FrameGate(SyncEstimator(20), VideoScheduler(maxReorderFrames = 0))

    fun onAudioFrame(mediaTs: Long, arrivalMs: Long) = gate.onAudio(mediaTs, arrivalMs)

    fun push(frame: io.streamrtc.protocol.RelayFrame) {
        decoder?.push(frame)
    }

    override fun attach(surface: android.view.Surface) {
        if (decoder != null) return
        _state.value = RemoteVideoState.WaitingForKeyframe
        decoder = VideoDecoder(slot, surface, { _state.value = it }, { /* onNeedKeyframe: wired by SessionManager */ }, logger)
    }

    override fun detach() {
        decoder?.stop()
        decoder = null
        _state.value = RemoteVideoState.Idle
    }
}
