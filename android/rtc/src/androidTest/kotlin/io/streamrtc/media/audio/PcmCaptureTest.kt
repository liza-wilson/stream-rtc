package io.streamrtc.media.audio

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.streamrtc.api.AudioConfig
import io.streamrtc.api.RtcLogger
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PcmCaptureTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun producesTwentyMillisecondPcmFrames() {
        val frames = LinkedBlockingQueue<ByteArray>()
        val capture = PcmCapture(AudioConfig.Default, RtcLogger.None)
        capture.start { bytes, _ -> frames.put(bytes) }
        try {
            val first = frames.poll(5, TimeUnit.SECONDS)!!
            assertEquals(640, first.size)
        } finally {
            capture.stop()
        }
    }
}
