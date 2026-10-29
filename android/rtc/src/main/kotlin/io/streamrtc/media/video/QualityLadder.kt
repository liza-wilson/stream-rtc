package io.streamrtc.media.video

import io.streamrtc.api.VideoConfig

/**
 * Adaptive quality ladder for the video encoder.
 *
 * The ladder starts at [start] and spans the tiers from [start] down to
 * [VideoConfig.Low]. While the relay reports too much queued bitrate (more than
 * [BACKLOG_LIMIT_BYTES] continuously) the encoder is stepped to the next lower
 * tier; while the queue drains (below [HEADROOM_LIMIT_BYTES] continuously) it is
 * stepped back up towards [start]. Steps never run past the bottom of the ladder
 * or above [start].
 */
class QualityLadder(private val start: VideoConfig) {

    private val tiers: List<VideoConfig> = when (start) {
        VideoConfig.Low -> listOf(VideoConfig.Low)
        VideoConfig.Standard -> listOf(VideoConfig.Standard, VideoConfig.Low)
        VideoConfig.High -> listOf(VideoConfig.High, VideoConfig.Standard, VideoConfig.Low)
        else -> listOf(start)
    }

    private var index = 0
    private var backlogSinceMs: Long? = null
    private var headroomSinceMs: Long? = null

    /** The tier the encoder should currently run with. */
    val current: VideoConfig get() = tiers[index]

    /**
     * Consumes one relay queue sample and returns the tier to switch to when a
     * step fired, or null when no step happened. A window only completes once the
     * streak it measures has lasted long enough; a sample outside the active
     * window resets that window's timer.
     */
    fun onQueueSample(queuedBytes: Long, nowMs: Long): VideoConfig? {
        var result: VideoConfig? = null
        when {
            queuedBytes > BACKLOG_LIMIT_BYTES -> {
                if (backlogSinceMs == null) backlogSinceMs = nowMs
                if (nowMs - (backlogSinceMs ?: nowMs) >= BACKLOG_WINDOW_MS) {
                    backlogSinceMs = null
                    val next = index + 1
                    if (next < tiers.size) {
                        index = next
                        result = tiers[next]
                    }
                }
            }

            queuedBytes < HEADROOM_LIMIT_BYTES -> {
                if (headroomSinceMs == null) headroomSinceMs = nowMs
                if (nowMs - (headroomSinceMs ?: nowMs) >= HEADROOM_WINDOW_MS) {
                    headroomSinceMs = null
                    val previous = index - 1
                    if (previous >= 0) {
                        index = previous
                        result = tiers[previous]
                    }
                }
            }

            else -> {
                backlogSinceMs = null
                headroomSinceMs = null
            }
        }
        return result
    }

    private companion object {
        const val BACKLOG_LIMIT_BYTES = 64 * 1024L
        const val BACKLOG_WINDOW_MS = 2_000L
        const val HEADROOM_LIMIT_BYTES = 8 * 1024L
        const val HEADROOM_WINDOW_MS = 15_000L
    }
}
