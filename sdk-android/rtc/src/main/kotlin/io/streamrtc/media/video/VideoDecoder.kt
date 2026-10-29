package io.streamrtc.media.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.streamrtc.api.RemoteVideoState
import io.streamrtc.protocol.FrameCodec
import io.streamrtc.protocol.H264AnnexB
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Per-slot H.264 decoder. Configures lazily from the first keyframe's CSD, renders
 * to [surface], and reports [RemoteVideoState] transitions through [onState].
 * Stalls reset the decoder and ask the sender for a fresh keyframe (debounced).
 */
class VideoDecoder(
    private val slot: Int,
    private val surface: Surface,
    private val onState: (RemoteVideoState) -> Unit,
    private val onNeedKeyframe: () -> Unit,
    private val logger: io.streamrtc.api.RtcLogger,
) {
    @Volatile
    var state: RemoteVideoState = RemoteVideoState.WaitingForKeyframe
        private set

    private val queue = ConcurrentLinkedQueue<io.streamrtc.protocol.RelayFrame>()

    private var codec: MediaCodec? = null
    private var configured = false
    private var running = false
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var playingReported = false
    private var lastNeedKeyframeMs = 0L
    private var lastInputMs = 0L

    init {
        val t = HandlerThread("streamrtc-decode-$slot").also { it.start() }
        thread = t
        running = true
        handler = Handler(t.looper)
        handler?.post { pump() }
    }

    fun push(frame: io.streamrtc.protocol.RelayFrame) {
        if (frame.type != FrameCodec.TYPE_H264) return
        queue.offer(frame)
    }

    private fun pump() {
        while (running) {
            val c = codec
            if (c == null) {
                val next = queue.peek()
                if (next == null) {
                    Thread.sleep(5)
                    continue
                }
                val csd = H264AnnexB.csd(next.payload)
                if (csd == null) {
                    queue.poll()
                    requestKeyframeDebounced()
                    // state stays WaitingForKeyframe; only report if not already
                    if (state != RemoteVideoState.WaitingForKeyframe) setState(RemoteVideoState.WaitingForKeyframe)
                    continue
                }
                try {
                    val format = MediaFormat.createVideoFormat("video/avc", 0, 0).apply {
                        setByteBuffer("csd-0", ByteBuffer.wrap(csd.sps))
                        setByteBuffer("csd-1", ByteBuffer.wrap(csd.pps))
                    }
                    val newCodec = MediaCodec.createDecoderByType("video/avc")
                    newCodec.configure(format, surface, null, 0)
                    newCodec.start()
                    codec = newCodec
                    configured = true
                } catch (t: Throwable) {
                    logger.e("VideoDecoder", "configure failed", t)
                    queue.poll()
                    requestKeyframeDebounced()
                    if (state != RemoteVideoState.WaitingForKeyframe) setState(RemoteVideoState.WaitingForKeyframe)
                    Thread.sleep(50)
                    continue
                }
                // feed the triggering frame below
            }

            val codecNow = codec ?: continue

            // feed one input if available
            val inputIndex = codecNow.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val frame = queue.poll()
                if (frame != null) {
                    val buf = codecNow.getInputBuffer(inputIndex)
                    buf?.clear()
                    buf?.put(frame.payload)
                    codecNow.queueInputBuffer(inputIndex, 0, frame.payload.size, frame.mediaTs * 1000L, 0)
                    lastInputMs = System.currentTimeMillis()
                } else {
                    codecNow.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                }
            }

            // drain one output
            val info = MediaCodec.BufferInfo()
            val outIndex = codecNow.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex >= 0 -> {
                    if (!playingReported) {
                        playingReported = true
                        setState(RemoteVideoState.Playing)
                    }
                    codecNow.releaseOutputBuffer(outIndex, true)
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* ignore */ }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* nothing */ }
            }

            // stall detection
            if (configured && !playingReported && lastInputMs > 0 &&
                System.currentTimeMillis() - lastInputMs > 1000) {
                resetCodec()
                requestKeyframeDebounced()
                if (state != RemoteVideoState.WaitingForKeyframe) setState(RemoteVideoState.WaitingForKeyframe)
                lastInputMs = 0
            }
        }
    }

    private fun setState(s: RemoteVideoState) {
        state = s
        onState(s)
    }

    private fun requestKeyframeDebounced() {
        val now = System.currentTimeMillis()
        if (now - lastNeedKeyframeMs > 1000) {
            lastNeedKeyframeMs = now
            onNeedKeyframe()
        }
    }

    private fun resetCodec() {
        codec?.stop()
        codec?.release()
        codec = null
        configured = false
        playingReported = false
        queue.clear()
    }

    fun stop() {
        running = false
        thread?.quitSafely()
        thread?.join(2000)
        thread = null
        codec?.stop()
        codec?.release()
        codec = null
    }
}
