package io.streamrtc.media.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import io.streamrtc.api.AudioConfig
import io.streamrtc.api.RtcLogger
import kotlin.math.max

/**
 * Microphone capture for one call: reads mono PCM16 from
 * [MediaRecorder.AudioSource.VOICE_COMMUNICATION] in exact [AudioConfig.frameBytes]
 * chunks and hands every chunk to the caller's callback.
 *
 * The read loop runs on its own `streamrtc-capture` thread at
 * [Process.THREAD_PRIORITY_URGENT_AUDIO] — never on the main thread. Each frame
 * is a freshly allocated [ByteArray], so the callback may queue or send it
 * asynchronously; the internal read buffer is reused and never escapes.
 *
 * [AcousticEchoCanceler] and [NoiseSuppressor] are attached to the record's
 * audio session when the config asks for them and the device advertises
 * support. [start] is idempotent while capturing, [stop] is idempotent and safe
 * to call from the callback thread (it never joins the current thread).
 */
class PcmCapture(
    private val config: AudioConfig,
    private val logger: RtcLogger = RtcLogger.None,
) {
    private val lock = Any()
    private var session: CaptureSession? = null

    /**
     * Starts capturing and delivers each exact-size PCM frame with an
     * `elapsedRealtime` timestamp in milliseconds. Does nothing if already
     * running or if the microphone cannot be opened.
     */
    fun start(onFrame: (ByteArray, Long) -> Unit) {
        synchronized(lock) {
            if (session != null) return

            val minBufferSize = AudioRecord.getMinBufferSize(
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBufferSize <= 0) {
                logger.e(TAG, "AudioRecord.getMinBufferSize returned $minBufferSize")
                return
            }

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    config.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBufferSize, config.frameBytes * BUFFER_FRAMES),
                )
            } catch (t: Throwable) {
                logger.e(TAG, "could not create AudioRecord", t)
                return
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                logger.e(TAG, "AudioRecord is not initialized")
                record.release()
                return
            }

            val capture = CaptureSession(
                record = record,
                echoCanceler = attachEchoCanceler(record.audioSessionId),
                noiseSuppressor = attachNoiseSuppressor(record.audioSessionId),
            )
            val thread = Thread({ readLoop(capture, onFrame) }, CAPTURE_THREAD)
            capture.thread = thread
            session = capture
            thread.start()
        }
    }

    /** Stops capturing and releases the microphone. Idempotent. */
    fun stop() {
        val current = synchronized(lock) {
            val running = session ?: return
            running.active = false
            running
        }
        // Unblocks a pending AudioRecord.read on the capture thread.
        runCatching { current.record.stop() }

        val thread = current.thread
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(JOIN_TIMEOUT_MS) }
        }
        release(current)
    }

    private fun readLoop(session: CaptureSession, onFrame: (ByteArray, Long) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ByteArray(config.frameBytes)
        var filled = 0

        try {
            session.record.startRecording()
            if (session.record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                logger.e(TAG, "AudioRecord did not enter the recording state")
                return
            }

            while (session.active) {
                val read = session.record.read(buffer, filled, buffer.size - filled)
                when {
                    read > 0 -> {
                        filled += read
                        if (filled == buffer.size) {
                            // The callback owns this frame; never hand out `buffer`.
                            val frame = buffer.copyOf(buffer.size)
                            filled = 0
                            val timestampMs = SystemClock.elapsedRealtime()
                            try {
                                onFrame(frame, timestampMs)
                            } catch (t: Throwable) {
                                logger.e(TAG, "capture callback failed", t)
                            }
                        }
                    }

                    read == 0 -> Thread.yield()

                    else -> {
                        if (session.active) logger.e(TAG, "AudioRecord.read failed with $read")
                        return
                    }
                }
            }
        } catch (t: Throwable) {
            logger.e(TAG, "capture loop failed", t)
        } finally {
            release(session)
        }
    }

    private fun release(session: CaptureSession) {
        synchronized(lock) {
            if (session.released) return
            session.released = true
            session.active = false

            runCatching { session.record.stop() }
            runCatching { session.record.release() }
            session.echoCanceler?.let { runCatching { it.release() } }
            session.noiseSuppressor?.let { runCatching { it.release() } }

            if (this.session === session) this.session = null
        }
    }

    private fun attachEchoCanceler(audioSessionId: Int): AcousticEchoCanceler? {
        if (!config.echoCancellation || !AcousticEchoCanceler.isAvailable()) return null
        return try {
            AcousticEchoCanceler.create(audioSessionId)?.also { it.enabled = true }
        } catch (t: Throwable) {
            logger.e(TAG, "could not attach AcousticEchoCanceler", t)
            null
        }
    }

    private fun attachNoiseSuppressor(audioSessionId: Int): NoiseSuppressor? {
        if (!config.noiseSuppression || !NoiseSuppressor.isAvailable()) return null
        return try {
            NoiseSuppressor.create(audioSessionId)?.also { it.enabled = true }
        } catch (t: Throwable) {
            logger.e(TAG, "could not attach NoiseSuppressor", t)
            null
        }
    }

    private class CaptureSession(
        val record: AudioRecord,
        val echoCanceler: AcousticEchoCanceler?,
        val noiseSuppressor: NoiseSuppressor?,
    ) {
        @Volatile var active = true
        @Volatile var released = false
        @Volatile var thread: Thread? = null
    }

    private companion object {
        const val TAG = "PcmCapture"
        const val CAPTURE_THREAD = "streamrtc-capture"
        const val BUFFER_FRAMES = 4
        const val JOIN_TIMEOUT_MS = 1_000L
    }
}
