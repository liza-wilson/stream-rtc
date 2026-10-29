package io.streamrtc.media.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Speaker playback for one received audio stream.
 *
 * [write] only queues a frame; a dedicated `streamrtc-playback` thread drains
 * the queue into a streaming [AudioTrack] at
 * [Process.THREAD_PRIORITY_URGENT_AUDIO]. The queue is bounded to roughly
 * [QUEUE_MS] of audio: when a network stall fills it, the oldest frame is
 * dropped so the sender (the network thread) never blocks.
 *
 * [write] never throws. A payload whose size is not exactly `frameBytes` is
 * dropped and logged, because it can only corrupt the PCM stream.
 * [start] and [stop] are idempotent; [stop] never joins the calling thread, so
 * it cannot deadlock if invoked from the playback thread.
 */
class PcmPlayback(
    private val sampleRate: Int,
    private val frameBytes: Int,
) {
    private val frameMs = ((frameBytes * 1_000L) / (sampleRate * 2L)).toInt().coerceAtLeast(1)
    private val capacity = (QUEUE_MS / frameMs).coerceAtLeast(MIN_QUEUE_FRAMES)
    private val frames = LinkedBlockingDeque<ByteArray>(capacity)
    private val lock = Any()

    private var session: PlaybackSession? = null

    /** Opens the track and starts the playback thread. Idempotent. */
    fun start() {
        synchronized(lock) {
            if (session != null) return
            frames.clear()
            val track = createTrack() ?: return
            val playback = PlaybackSession(track)
            val thread = Thread({ playbackLoop(playback) }, PLAYBACK_THREAD)
            playback.thread = thread
            session = playback
            thread.start()
        }
    }

    /**
     * Queues one PCM frame. A wrong-sized payload or a call before [start] is
     * dropped and logged; this method never throws and never blocks on the
     * audio queue.
     */
    fun write(bytes: ByteArray) {
        if (bytes.size != frameBytes) {
            Log.w(TAG, "dropping ${bytes.size}-byte frame; expected $frameBytes")
            return
        }
        synchronized(lock) {
            if (session?.active != true) {
                Log.w(TAG, "dropping frame; playback is not running")
                return
            }
            // Bounded queue: shed the oldest frame instead of blocking the sender.
            while (!frames.offerLast(bytes)) {
                frames.pollFirst()
            }
        }
    }

    /** Stops playback, clears the queue and releases the track. Idempotent. */
    fun stop() {
        val current = synchronized(lock) {
            val running = session ?: return
            running.active = false
            session = null
            frames.clear()
            running
        }
        // Unblocks a pending AudioTrack.write on the playback thread.
        runCatching { current.track.stop() }

        val thread = current.thread
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(JOIN_TIMEOUT_MS) }
        }
        release(current)
    }

    private fun playbackLoop(session: PlaybackSession) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            session.track.play()
            if (session.track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                Log.e(TAG, "AudioTrack did not enter the playing state")
                return
            }

            while (session.active) {
                val frame = nextFrame() ?: continue
                var offset = 0
                while (offset < frame.size && session.active) {
                    val written = session.track.write(frame, offset, frame.size - offset)
                    if (written <= 0) {
                        Log.e(TAG, "AudioTrack.write returned $written")
                        return
                    }
                    offset += written
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "playback loop failed", t)
        } finally {
            release(session)
        }
    }

    private fun nextFrame(): ByteArray? = try {
        frames.pollFirst(FRAME_WAIT_MS, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    private fun createTrack(): AudioTrack? {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            Log.e(TAG, "AudioTrack.getMinBufferSize returned $minBufferSize")
            return null
        }

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBufferSize, frameBytes * BUFFER_FRAMES))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "could not create AudioTrack", t)
            return null
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack is not initialized")
            track.release()
            return null
        }
        return track
    }

    private fun release(session: PlaybackSession) {
        synchronized(lock) {
            if (session.released) return
            session.released = true
            session.active = false
            runCatching { session.track.stop() }
            runCatching { session.track.release() }
            if (this.session === session) this.session = null
        }
    }

    private class PlaybackSession(val track: AudioTrack) {
        @Volatile var active = true
        @Volatile var released = false
        @Volatile var thread: Thread? = null
    }

    private companion object {
        const val TAG = "PcmPlayback"
        const val PLAYBACK_THREAD = "streamrtc-playback"
        const val QUEUE_MS = 2_000
        const val MIN_QUEUE_FRAMES = 4
        const val BUFFER_FRAMES = 4
        const val FRAME_WAIT_MS = 50L
        const val JOIN_TIMEOUT_MS = 1_000L
    }
}
