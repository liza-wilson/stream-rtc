package io.streamrtc.media.video

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class CameraErrorTest {

    @Test fun cameraUnavailableDeliversTypedError() {
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val owner = TestLifecycleOwner()
        val registry = owner.registry
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        val controller = CameraController(ApplicationProvider.getApplicationContext(), owner) { errors.add(it) }
        val surface = Surface(SurfaceTexture(false))
        controller.bindTo(surface)
        assertEquals(1, errors.size)
    }
}

/** A [LifecycleOwner] whose lifecycle is parked in the [Lifecycle.State.DESTROYED] state. */
private class TestLifecycleOwner : LifecycleOwner {
    val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)

    override val lifecycle: Lifecycle get() = registry
}
