package io.streamrtc.media.video

import io.streamrtc.api.VideoConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityLadderTest {

    @Test fun stepsDownAfterTwoSecondsOfBacklog() {
        val q = QualityLadder(VideoConfig.Standard)
        assertNull(q.onQueueSample(80_000, 0))
        assertNull(q.onQueueSample(80_000, 1_000))
        assertEquals(VideoConfig.Low, q.onQueueSample(80_000, 2_000))
    }

    @Test fun stepsUpAfterFifteenSecondsOfHeadroom() {
        val q = QualityLadder(VideoConfig.Standard)
        assertNull(q.onQueueSample(80_000, 0))
        assertNull(q.onQueueSample(80_000, 1_000))
        assertEquals(VideoConfig.Low, q.onQueueSample(80_000, 2_000))
        assertNull(q.onQueueSample(4_096, 2_000))
        assertNull(q.onQueueSample(4_096, 10_000))
        assertEquals(VideoConfig.Standard, q.onQueueSample(4_096, 17_000))
    }

    @Test fun neverGoesBelowLowOrAboveTheCeiling() {
        val q = QualityLadder(VideoConfig.Low)
        q.onQueueSample(80_000, 1_000)
        q.onQueueSample(80_000, 2_000)
        assertNull(q.onQueueSample(80_000, 3_000))
        assertEquals(VideoConfig.Low, q.current)
    }
}
