package io.streamrtc.media.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PcmPlaybackTest {
    @Test
    fun acceptsSilenceWithoutThrowing() {
        val playback = PcmPlayback(16_000, 640)
        playback.start()
        try {
            repeat(10) { playback.write(ByteArray(640)) }
        } finally {
            playback.stop()
        }
    }
}
