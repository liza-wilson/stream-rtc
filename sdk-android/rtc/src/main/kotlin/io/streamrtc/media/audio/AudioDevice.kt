package io.streamrtc.media.audio

import android.content.Context
import io.streamrtc.api.AudioConfig
import io.streamrtc.api.RtcLogger
import java.util.concurrent.ConcurrentHashMap

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
 * Routing starts lazily on the first capture or playback call. Focus changes
 * drive capture: `Paused` stops it, `Resumed` restarts it only when it was
 * running at pause time, and `Lost` stops it and invokes [onFocusLost] so the
 * session can end the call. [AudioConfig.Default] is the call's fixed audio
 * format.
 */
class DefaultAudioDevice(
    context: Context,
    private val logger: RtcLogger = RtcLogger.None,
    private val onFocusLost: () -> Unit = {},
) : AudioDevice {
    private val config = AudioConfig.Default
    private val router = AudioRouter(context)
    private val lock = Any()
    private val slots = ConcurrentHashMap<Int, PcmPlayback>()

    private var capture: PcmCapture? = null
    private var captureHandler: ((ByteArray, Long) -> Unit)? = null
    private var captureRunning = false
    private var resumeAfterFocus = false
    private var routingStarted = false
    private var speakerphone = false
    private var closed = false

    init {
        router.onFocusChange = ::handleFocusChange
    }

    override fun startCapture(onFrame: (ByteArray, Long) -> Unit) {
        synchronized(lock) {
            if (closed) return
            captureHandler = onFrame
            ensureRoutingLocked()
            val current = capture ?: PcmCapture(config, logger).also { capture = it }
            resumeAfterFocus = false
            captureRunning = true
            // Start under the lock so a focus loss cannot race between marking
            // capture as running and actually opening the microphone.
            current.start(onFrame)
        }
    }

    override fun stopCapture() {
        val capture = synchronized(lock) {
            captureRunning = false
            resumeAfterFocus = false
            captureHandler = null
            capture
        }
        capture?.stop()
    }

    override fun write(slot: Int, payload: ByteArray) {
        val playback = synchronized(lock) {
            if (closed) return
            ensureRoutingLocked()
            slots.getOrPut(slot) {
                PcmPlayback(config.sampleRate, config.frameBytes).also { it.start() }
            }
        }
        playback.write(payload)
    }

    override fun removeSlot(slot: Int) {
        slots.remove(slot)?.stop()
    }

    override fun setSpeakerphone(on: Boolean) {
        synchronized(lock) {
            speakerphone = on
            if (routingStarted) router.setSpeakerphone(on)
        }
    }

    override fun close() {
        val (capture, playbacks) = synchronized(lock) {
            if (closed) return
            closed = true
            captureRunning = false
            resumeAfterFocus = false
            captureHandler = null
            val currentCapture = capture
            capture = null
            val currentPlaybacks = slots.values.toList()
            slots.clear()
            currentCapture to currentPlaybacks
        }

        capture?.stop()
        playbacks.forEach { it.stop() }
        router.stop()
    }

    private fun ensureRoutingLocked() {
        if (routingStarted) return
        routingStarted = true
        router.start(speakerphone)
    }

    private fun handleFocusChange(state: AudioFocusState) {
        when (state) {
            AudioFocusState.Paused -> {
                val capture = synchronized(lock) {
                    if (!captureRunning) return
                    captureRunning = false
                    resumeAfterFocus = true
                    capture
                }
                capture?.stop()
            }

            AudioFocusState.Resumed -> {
                synchronized(lock) {
                    if (!resumeAfterFocus || closed) return
                    val handler = captureHandler ?: return
                    resumeAfterFocus = false
                    val current = capture ?: PcmCapture(config, logger).also { capture = it }
                    captureRunning = true
                    current.start(handler)
                }
            }

            AudioFocusState.Lost -> {
                val capture = synchronized(lock) {
                    captureRunning = false
                    resumeAfterFocus = false
                    capture
                }
                capture?.stop()
                onFocusLost()
            }
        }
    }
}
