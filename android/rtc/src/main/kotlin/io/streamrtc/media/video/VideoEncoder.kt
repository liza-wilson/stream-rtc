package io.streamrtc.media.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.streamrtc.api.RtcLogger
import io.streamrtc.api.VideoConfig
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * H.264 (Baseline profile, CBR) encoder fed by a camera surface. Frames come out
 * of the codec on a dedicated handler thread and are handed to [onFrame] with the
 * Annex-B keyframe flag and a monotonically increasing media timestamp.
 */
class VideoEncoder(
    private var config: VideoConfig,
    private val onFrame: (ByteArray, Boolean, Long) -> Unit,
    private val logger: RtcLogger,
) {

    var surface: Surface? = null


    private var codec: MediaCodec? = null
    private var handlerThread: HandlerThread? = null
    private var running = false
    private val frameCounter = AtomicLong(0L)

    fun start() {
        stop()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeIntervalSec)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setString(MediaFormat.KEY_PROFILE, "avc1")
            setString(MediaFormat.KEY_LEVEL, "30")
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        this.surface = inputSurface
        codec.start()
        this.codec = codec
        running = true
        val thread = HandlerThread("streamrtc-encoder").also { it.start() }
        handlerThread = thread
        Handler(thread.looper).post { pump() }
    }

    fun requestKeyframe() {
        codec?.setParameters(
            android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) },
        )
    }

    fun reconfigure(newConfig: VideoConfig) {
        stop()
        config = newConfig
        start()
    }

    fun stop() {
        running = false
        handlerThread?.quitSafely()
        handlerThread = null
        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        try {
            surface?.release()
        } catch (_: Exception) {}
        surface = null
    }

    private var spsPps: ByteArray? = null

    private fun pump() {
        val codec = codec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (index < 0) continue
            val buffer = codec.getOutputBuffer(index) ?: ByteBuffer.allocate(1)
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                spsPps = bytes
                codec.releaseOutputBuffer(index, false)
                continue
            }

            val keyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            val payload = if (keyframe && spsPps != null) {
                spsPps!! + bytes
            } else {
                bytes
            }
            onFrame(payload, keyframe, frameCounter.getAndIncrement())
            codec.releaseOutputBuffer(index, false)
        }
    }
}
