package io.streamrtc.media

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameGateTest {
    @Test fun rendersVideoBeforeAnyAudioHasArrived() {
        val gate = FrameGate()
        assertEquals(VideoAction.Render, gate.videoAction(mediaTs = 5_000, arrivalMs = 5_000, nowMs = 5_000))
    }

    @Test fun holdsEarlyAndDropsLateOnceTheOffsetIsKnown() {
        val gate = FrameGate()
        repeat(20) { i -> gate.onAudio(1_000 + i * 20L, 1_000 + i * 20L + 40) }
        assertEquals(VideoAction.Hold, gate.videoAction(mediaTs = 1_200, arrivalMs = 1_240, nowMs = 1_200))
        assertEquals(VideoAction.Drop, gate.videoAction(mediaTs = 500, arrivalMs = 540, nowMs = 1_200))
        assertEquals(VideoAction.Render, gate.videoAction(mediaTs = 1_000, arrivalMs = 1_040, nowMs = 1_040))
    }
}
