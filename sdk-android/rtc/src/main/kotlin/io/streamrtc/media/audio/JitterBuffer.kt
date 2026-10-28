package io.streamrtc.media.audio

import kotlin.math.roundToInt

/**
 * Adaptive jitter buffer for one remote audio stream: a bounded FIFO of
 * `(mediaTs, payload)` frames drained by the playback loop.
 *
 * `mediaTs` is the sender's 32-bit millisecond clock carried in a [Long] and it
 * wraps every ~49.7 days. Every timestamp comparison therefore goes through
 * [delta], a signed 32-bit difference, so a wrap is just another small step —
 * never a 2^32 jump.
 *
 * Prefill: [poll] returns `null` until the buffer holds at least
 * [targetDepthMs]; once primed it drains continuously (and re-primes after an
 * underrun) so the playback loop is never starved by the depth estimate.
 *
 * Target depth is `clamp(40 + 3 × EMA(lateness), 40, 200)` ms, where lateness
 * is how much later than `baseArrival + (mediaTs - baseTs)` a frame showed up
 * (early arrivals count as zero — they add no playout risk). The baseline is
 * the first frame accepted after construction or [reset].
 *
 * The buffer treats payloads as opaque: [frameBytes] documents the expected
 * PCM frame size for callers, but a wrongly sized payload is simply stored and
 * returned. Validation belongs at the media ingest boundary; dropping by size
 * here would silently stall the audio loop.
 *
 * Not thread-safe: drive every method from the session's single executor (or the
 * per-slot playback loop), never concurrently.
 */
class JitterBuffer(
    val frameBytes: Int,
    private val frameMs: Int,
    capacityFrames: Int,
    private val clockMs: () -> Long,
) {
    private val capacity = capacityFrames.coerceAtLeast(1)
    private val frames = ArrayDeque<Pair<Long, ByteArray>>()

    private var haveBase = false
    private var baseTs = 0L
    private var baseArrivalMs = 0L
    private var lastTs = 0L
    private var lastDeliveredTs: Long? = null
    private var primed = false
    private var emaDeviationMs = 0.0

    /** Current buffered audio in milliseconds; `0` when empty. */
    val depthMs: Int
        get() = if (frames.isEmpty()) 0 else delta(lastTs, frames.first().first) + frameMs

    /** Adaptive prefill target in milliseconds, in `40..200`. */
    val targetDepthMs: Int
        get() = (BASE_TARGET_MS + TARGET_PER_DEVIATION_MS * emaDeviationMs)
            .roundToInt()
            .coerceIn(BASE_TARGET_MS, MAX_TARGET_MS)

    /**
     * Enqueues [payload] stamped [mediaTs]. Frames that are not newer than the
     * last delivered frame (or the newest buffered frame) are stale and dropped;
     * the oldest frame is dropped when the buffer is at capacity.
     */
    fun push(mediaTs: Long, payload: ByteArray) {
        lastDeliveredTs?.let { if (delta(mediaTs, it) <= 0) return }
        if (frames.isNotEmpty() && delta(mediaTs, lastTs) <= 0) return

        val arrivalMs = clockMs()
        if (!haveBase) {
            haveBase = true
            baseTs = mediaTs
            baseArrivalMs = arrivalMs
        } else {
            val expectedArrivalMs = baseArrivalMs + delta(mediaTs, baseTs)
            val latenessMs = (arrivalMs - expectedArrivalMs).coerceAtLeast(0L)
            emaDeviationMs += EMA_ALPHA * (latenessMs - emaDeviationMs)
        }

        if (frames.size >= capacity) frames.removeFirst()
        frames.addLast(mediaTs to payload)
        lastTs = mediaTs
    }

    /** Oldest frame, or `null` while empty or still prefilling to the target. */
    fun poll(): ByteArray? {
        if (frames.isEmpty()) return null
        if (!primed) {
            if (depthMs < targetDepthMs) return null
            primed = true
        }
        val (ts, payload) = frames.removeFirst()
        lastDeliveredTs = ts
        if (frames.isEmpty()) primed = false
        return payload
    }

    /** Forgets buffered frames, the jitter estimate and the prefill state. */
    fun reset() {
        frames.clear()
        haveBase = false
        baseTs = 0
        baseArrivalMs = 0
        lastTs = 0
        lastDeliveredTs = null
        primed = false
        emaDeviationMs = 0.0
    }

    /**
     * Signed 32-bit difference between two 32-bit millisecond timestamps: a wrap
     * from `0xFFFF_FFFF` to `0` is a delta of 1, not -4294967295.
     */
    private fun delta(newer: Long, older: Long): Int = (newer - older).toInt()

    private companion object {
        const val BASE_TARGET_MS = 40
        const val MAX_TARGET_MS = 200
        const val TARGET_PER_DEVIATION_MS = 3.0
        const val EMA_ALPHA = 0.125
    }
}
