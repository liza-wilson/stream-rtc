package io.streamrtc.media.audio

import android.content.Context
import io.streamrtc.api.AudioConfig
import io.streamrtc.api.RtcLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Media seam between the session and the platform audio stack: microphone
 * capture, per-slot playback and routing.
 *
 * Slots let one [AudioDevice] serve every remote participant without each
 * stream fighting the others; implementations create playback lazily per slot.
 */
interface AudioDevice {
    fun startCapture(onFrame: (ByteArray, Long) -> Unit)
    fun stopCapture()
    fun write(slot: Int, payload: ByteArray)
    fun removeSlot(slot: Int)
    fun setSpeakerphone(on: Boolean)
    fun close()
}

/**
 * Real [AudioDevice] backed by [PcmCapture], one [PcmPlayback] per slot and a
 * single [AudioRouter] for the call.
 *
 * All platform/HAL work — opening the microphone, creating an [android.media.AudioTrack],
 * routing and focus handling — runs on one daemon control thread
 * (`streamrtc-audio-control`). Public methods only mutate a little state and
 * enqueue work, so neither the main thread nor an audio thread ever blocks in
 * `AudioRecord`/`AudioTrack`/`AudioManager` calls. [write] stays on the caller's
 * thread: it only offers a frame to the slot's bounded queue (drop-oldest), so
 * the media path is never serialized behind a pending start/stop. A new slot's
 * playback object is constructed inline (pure Kotlin, no HAL) and started on the
 * control thread; frames arriving before that start are buffered per slot and
 * drained into the track once it exists.
 *
 * Focus changes are queued on the same control thread: `Paused` stops capture,
 * `Resumed` restarts it only when it was running at pause time and the session
 * never stopped it, and `Lost` stops it and invokes [onFocusLost] so the session
 * can end the call. The format is [AudioConfig.Default].
 */
class DefaultAudioDevice(
    context: Context,
    private val logger: RtcLogger = RtcLogger.None,
    private val onFocusLost: () -> Unit = {},
) : AudioDevice {
    private val config = AudioConfig.Default
    private val router = AudioRouter(context)
    private val lock = Any()
    private val slots = ConcurrentHashMap<Int, Slot>()

    @Volatile
    private var closed = false

    @Volatile
    private var speakerphone = false

    @Volatile
    private var controlThread: Thread? = null

    private var capture: PcmCapture? = null
    private var captureHandler: ((ByteArray, Long) -> Unit)? = null
    private var captureRunning = false
    private var resumeAfterFocus = false
    private var routingStarted = false

    private val control = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, CONTROL_THREAD).apply {
            isDaemon = true
            controlThread = this
        }
    }

    init {
        router.onFocusChange = ::handleFocusChange
    }

    override fun startCapture(onFrame: (ByteArray, Long) -> Unit) {
        submitControl {
            if (closed) return@submitControl
            captureHandler = onFrame
            ensureRoutingOnControl()
            val current = capture ?: PcmCapture(config, logger).also { capture = it }
            resumeAfterFocus = false
            captureRunning = true
            current.start(onFrame)
        }
    }

    override fun stopCapture() {
        submitControl {
            if (closed) return@submitControl
            captureHandler = null
            captureRunning = false
            resumeAfterFocus = false
            capture?.stop()
        }
    }

    override fun write(slot: Int, payload: ByteArray) {
        val target = slotFor(slot) ?: return
        target.enqueue(payload)
    }

    override fun removeSlot(slot: Int) {
        val removed = synchronized(lock) { slots.remove(slot) } ?: return
        if (!submitControl { removed.stop() }) removed.stop()
    }

    override fun setSpeakerphone(on: Boolean) {
        speakerphone = on
        submitControl {
            if (closed) return@submitControl
            if (routingStarted) router.setSpeakerphone(on)
        }
    }

    override fun close() {
        val first = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (!first) return

        if (isControlThread()) {
            shutdownMediaOnControl()
            control.shutdown()
            return
        }

        val queued = submitControl { shutdownMediaOnControl() }
        control.shutdown()
        if (!queued) {
            shutdownMediaOnControl()
        } else if (!isAudioThread()) {
            runCatching { control.awaitTermination(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        }
    }

    /**
     * Returns the slot, creating its playback lazily. The [PcmPlayback] object is
     * cheap and framework-free; its [PcmPlayback.start] (which builds the
     * AudioTrack) is queued on the control thread.
     */
    private fun slotFor(slot: Int): Slot? {
        var created: Slot? = null
        val target = synchronized(lock) {
            if (closed) return null
            slots.getOrPut(slot) {
                Slot(PcmPlayback(config.sampleRate, config.frameBytes), MAX_PENDING_FRAMES)
                    .also { created = it }
            }
        }

        val newSlot = created
        if (newSlot != null) {
            val queued = submitControl {
                if (closed) {
                    newSlot.stop()
                    return@submitControl
                }
                ensureRoutingOnControl()
                newSlot.start()
            }
            if (!queued) newSlot.stop()
        }
        return target
    }

    private fun ensureRoutingOnControl() {
        if (routingStarted) return
        routingStarted = true
        router.start(speakerphone)
    }

    private fun handleFocusChange(state: AudioFocusState) {
        submitControl {
            if (closed) return@submitControl
            when (state) {
                AudioFocusState.Paused -> {
                    if (captureRunning) {
                        captureRunning = false
                        resumeAfterFocus = true
                        capture?.stop()
                    }
                }

                AudioFocusState.Resumed -> {
                    val handler = captureHandler
                    if (resumeAfterFocus && handler != null) {
                        resumeAfterFocus = false
                        val current = capture ?: PcmCapture(config, logger).also { capture = it }
                        captureRunning = true
                        current.start(handler)
                    }
                }

                AudioFocusState.Lost -> {
                    captureRunning = false
                    resumeAfterFocus = false
                    capture?.stop()
                    onFocusLost()
                }
            }
        }
    }

    /** Runs on the control thread (or inline if the executor is already gone). */
    private fun shutdownMediaOnControl() {
        val currentCapture = capture
        capture = null
        captureHandler = null
        captureRunning = false
        resumeAfterFocus = false

        val playbacks = synchronized(lock) {
            val snapshot = slots.values.toList()
            slots.clear()
            snapshot
        }

        currentCapture?.stop()
        playbacks.forEach { it.stop() }
        router.stop()
    }

    private fun submitControl(task: () -> Unit): Boolean = try {
        control.execute {
            try {
                task()
            } catch (t: Throwable) {
                logger.e(TAG, "audio control task failed", t)
            }
        }
        true
    } catch (e: RejectedExecutionException) {
        false
    }

    private fun isControlThread(): Boolean = Thread.currentThread() === controlThread

    private fun isAudioThread(): Boolean {
        val name = Thread.currentThread().name
        return name == CAPTURE_THREAD || name == PLAYBACK_THREAD
    }

    /**
     * One output stream plus a short pre-start buffer. `start`/`stop` are called
     * from the control thread; `enqueue` comes from arbitrary media threads and
     * never blocks.
     */
    private class Slot(
        private val playback: PcmPlayback,
        private val maxPending: Int,
    ) {
        private val lock = Any()
        private val pending = ArrayDeque<ByteArray>()
        private var started = false
        private var closed = false

        fun enqueue(bytes: ByteArray) {
            val direct = synchronized(lock) {
                if (closed) return
                if (started) {
                    true
                } else {
                    if (pending.size >= maxPending) pending.removeFirst()
                    pending.addLast(bytes)
                    false
                }
            }
            if (direct) playback.write(bytes)
        }

        fun start() {
            val queued = synchronized(lock) {
                if (closed) return
                started = true
                pending.toList().also { pending.clear() }
            }
            playback.start()
            queued.forEach(playback::write)
        }

        fun stop() {
            val target = synchronized(lock) {
                if (closed) return
                closed = true
                started = false
                pending.clear()
                playback
            }
            target.stop()
        }
    }

    private companion object {
        const val TAG = "DefaultAudioDevice"
        const val CONTROL_THREAD = "streamrtc-audio-control"
        const val CAPTURE_THREAD = "streamrtc-capture"
        const val PLAYBACK_THREAD = "streamrtc-playback"
        const val MAX_PENDING_FRAMES = 100
        const val CLOSE_TIMEOUT_MS = 2_000L
    }
}
