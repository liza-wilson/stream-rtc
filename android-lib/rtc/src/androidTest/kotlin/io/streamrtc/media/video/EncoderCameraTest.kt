package io.streamrtc.media.video

import android.Manifest
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.streamrtc.api.RtcLogger
import io.streamrtc.api.VideoConfig
import io.streamrtc.protocol.H264AnnexB
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncoderCameraTest {

    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @get:Rule
    val scenario = ActivityScenarioRule<ComponentActivity>(ComponentActivity::class.java)

    @Test fun emitsAnAnnexBKeyframeWithinTenSeconds() {
        val frames = LinkedBlockingQueue<Pair<ByteArray, Boolean>>()
        val encoder = VideoEncoder(VideoConfig.Low, { bytes, key, _ -> frames.put(bytes to key) }, RtcLogger.None)
        encoder.start()
        val controller = CameraController(ApplicationProvider.getApplicationContext(), ComponentActivity()) { }
        controller.bindTo(Surface(SurfaceTexture(false)))
        try {
            val (bytes, key) = frames.poll(10, TimeUnit.SECONDS)!!
            assertTrue(key)
            assertNotNull(H264AnnexB.csd(bytes))
        } finally {
            controller.unbind()
            encoder.stop()
        }
    }
}
