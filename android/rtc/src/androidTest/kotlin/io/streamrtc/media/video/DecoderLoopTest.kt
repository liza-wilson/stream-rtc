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
import io.streamrtc.api.RemoteVideoState
import io.streamrtc.api.VideoConfig
import io.streamrtc.protocol.FrameCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DecoderLoopTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule val scenario = ActivityScenarioRule<ComponentActivity>(ComponentActivity::class.java)

    @Test fun decodesAnEncoderKeyframeAndReportsPlaying() {
        val encoded = LinkedBlockingQueue<Pair<ByteArray, Boolean>>()
        val encoder = VideoEncoder(VideoConfig.Low, { b, k, _ -> encoded.put(b to k) }, RtcLogger.None)
        encoder.start()
        val controller = CameraController(ApplicationProvider.getApplicationContext(), ComponentActivity()) { }
        controller.bindTo(encoder.surface!!)

        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        val states = LinkedBlockingQueue<RemoteVideoState>()
        val decoder = VideoDecoder(0, surface, { states.put(it) }, {}, RtcLogger.None)
        try {
            val first = encoded.poll(10, TimeUnit.SECONDS)!!
            decoder.push(FrameCodec.decodeRelay(FrameCodec.encodeRelay(FrameCodec.TYPE_H264, if (first.second) 1 else 0, 0, 100, first.first))!!)
            assertEquals(RemoteVideoState.Playing, states.poll(10, TimeUnit.SECONDS))
        } finally {
            decoder.stop(); texture.release(); surface.release(); controller.unbind(); encoder.stop()
        }
    }

    @Test fun ignoresDeltaFramesUntilAKeyframeArrives() {
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        val states = LinkedBlockingQueue<RemoteVideoState>()
        val decoder = VideoDecoder(0, surface, { states.put(it) }, {}, RtcLogger.None)
        try {
            val delta = FrameCodec.decodeRelay(
                FrameCodec.encodeRelay(FrameCodec.TYPE_H264, 0, 0, 100, byteArrayOf(0, 0, 0, 1, 0x41, 1, 2)),
            )!!
            decoder.push(delta)
            assertNull(states.poll(1, TimeUnit.SECONDS))
            assertEquals(RemoteVideoState.WaitingForKeyframe, decoder.state)
        } finally {
            decoder.stop(); texture.release(); surface.release()
        }
    }
}
