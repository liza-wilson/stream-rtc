package io.streamrtc.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncTest {
    @Test fun offsetIsTheMedianOfAudioArrivalDeltas() {
        val s = SyncEstimator(minSamples = 5)
        repeat(5) { i -> s.onAudioArrival(i * 20L, i * 20L + if (i == 2) 300 else 40) }
        assertEquals(40L, s.offsetMs())
    }

    @Test fun reportsNullUntilEnoughSamples() {
        val s = SyncEstimator(minSamples = 5)
        s.onAudioArrival(0, 40)
        assertNull(s.offsetMs())
    }

    @Test fun schedulerRendersOnTimeDropsLateAndHoldsEarly() {
        val v = VideoScheduler(lateDropMs = 100)
        assertEquals(VideoAction.Render, v.action(dueAtMs = 1_000, nowMs = 1_050))
        assertEquals(VideoAction.Hold, v.action(dueAtMs = 1_200, nowMs = 1_050))
        assertEquals(VideoAction.Drop, v.action(dueAtMs = 900, nowMs = 1_050))
    }

    @Test fun resetForgetsTheEstimate() {
        val s = SyncEstimator(minSamples = 1)
        s.onAudioArrival(0, 40)
        s.reset()
        assertNull(s.offsetMs())
    }
}
