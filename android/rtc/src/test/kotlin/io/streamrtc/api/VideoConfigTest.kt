package io.streamrtc.api

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoConfigTest {
    @Test
    fun presetsMatchTheSpec() {
        assertEquals(640, VideoConfig.Standard.width)
        assertEquals(360, VideoConfig.Standard.height)
        assertEquals(24, VideoConfig.Standard.fps)
        assertEquals(700_000, VideoConfig.Standard.bitrate)
        assertEquals(320, VideoConfig.Low.width)
        assertEquals(1_500_000, VideoConfig.High.bitrate)
        assertEquals(2, VideoConfig.Standard.keyframeIntervalSec)
    }

    @Test
    fun audioDefaultsToPcm16TwentyMillisecondFrames() {
        assertEquals(16_000, AudioConfig.Default.sampleRate)
        assertEquals(20, AudioConfig.Default.frameMs)
        assertEquals(640, AudioConfig.Default.frameMs * AudioConfig.Default.sampleRate / 1000 * 2)
        assertEquals(640, AudioConfig.Default.frameBytes)
    }
}
