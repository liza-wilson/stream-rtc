package io.streamrtc.media

/**
 * Per-sender A/V clock estimate: `offset = arrivalMs - mediaTs`, so audio is the
 * master and a video frame stamped `mediaTs` is due at `mediaTs + offset`.
 *
 * The offset is the median of the last [MAX_SAMPLES] audio arrival deltas — a
 * median, not a mean, so a single late frame cannot shift the clock. [offsetMs]
 * stays `null` until [minSamples] samples have been collected; callers render
 * video immediately while the answer is unknown.
 *
 * `mediaTs` is the sender's 32-bit millisecond clock in a [Long] and it wraps.
 * Samples are unwrapped against the previous one with a signed 32-bit delta
 * before the offset is computed, so the estimate stays continuous across the
 * wrap instead of jumping by 2^32.
 *
 * If [minSamples] exceeds the 32-sample window, [offsetMs] can never return a
 * value; callers should pass the spec's 20. Not thread-safe: feed audio and read
 * the offset from the session's single executor.
 */
class SyncEstimator(minSamples: Int) {
    private val minSamples = minSamples.coerceAtLeast(1)
    private val deltas = ArrayDeque<Long>()

    private var haveBase = false
    private var lastRawTs = 0L
    private var lastUnwrappedTs = 0L

    /** Records one audio frame's sender stamp and local arrival time. */
    fun onAudioArrival(mediaTs: Long, arrivalMs: Long) {
        val unwrappedTs = if (haveBase) {
            lastUnwrappedTs + delta(mediaTs, lastRawTs)
        } else {
            haveBase = true
            mediaTs
        }
        lastRawTs = mediaTs
        lastUnwrappedTs = unwrappedTs

        deltas.addLast(arrivalMs - unwrappedTs)
        if (deltas.size > MAX_SAMPLES) deltas.removeFirst()
    }

    /** Median `arrivalMs - mediaTs`, or `null` until [minSamples] samples exist. */
    fun offsetMs(): Long? {
        if (deltas.size < minSamples) return null
        val sorted = deltas.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else sorted[mid - 1] + (sorted[mid] - sorted[mid - 1]) / 2
    }

    /** Forgets every sample and the unwrap baseline. */
    fun reset() {
        deltas.clear()
        haveBase = false
        lastRawTs = 0
        lastUnwrappedTs = 0
    }

    /** Signed 32-bit difference: wraps from `0xFFFF_FFFF` to `0` are a delta of 1. */
    private fun delta(newer: Long, older: Long): Int = (newer - older).toInt()

    private companion object {
        /** Longest window the median is computed over. */
        const val MAX_SAMPLES = 32
    }
}

/** What to do with a decoded video frame whose playout clock is known. */
enum class VideoAction { Render, Drop, Hold }

/**
 * Decides whether a video frame is due, too late, or too early to render.
 *
 * - `nowMs - dueAtMs > lateDropMs`: the frame is stale → [VideoAction.Drop].
 * - `dueAtMs - nowMs > maxReorderFrames × [FRAME_BUDGET_MS]`: the frame is
 *   early → [VideoAction.Hold] in the reorder queue.
 * - Otherwise → [VideoAction.Render].
 *
 * The default reorder horizon is 2 × 50 ms = 100 ms, inside the spec's
 * 4-frame / 200 ms queue. Callers that want the full 200 ms pass
 * `maxReorderFrames = 4`. The late bound defaults to the spec's 100 ms.
 */
class VideoScheduler(
    maxReorderFrames: Int = DEFAULT_MAX_REORDER_FRAMES,
    private val lateDropMs: Long = DEFAULT_LATE_DROP_MS,
) {
    private val reorderHorizonMs = maxReorderFrames.coerceAtLeast(0).toLong() * FRAME_BUDGET_MS

    fun action(dueAtMs: Long, nowMs: Long): VideoAction {
        if (nowMs - dueAtMs > lateDropMs) return VideoAction.Drop
        if (dueAtMs - nowMs > reorderHorizonMs) return VideoAction.Hold
        return VideoAction.Render
    }

    private companion object {
        /** Playout budget of one video frame at 20 fps. */
        const val FRAME_BUDGET_MS = 50L
        const val DEFAULT_MAX_REORDER_FRAMES = 2
        const val DEFAULT_LATE_DROP_MS = 100L
    }
}
