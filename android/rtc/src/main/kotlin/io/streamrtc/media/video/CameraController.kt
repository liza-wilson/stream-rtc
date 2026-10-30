package io.streamrtc.media.video

import android.content.Context
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.streamrtc.api.LocalVideo
import io.streamrtc.api.VideoStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * CameraX camera that feeds an encoder input surface (the encoder's camera-facing
 * surface) and owns the local video pipeline. Bind it to a camera lifecycle; the
 * encoder surface is provided on demand through the [Preview] surface provider.
 *
 * A second [Preview] is layered on while a display surface is attached
 * ([attachDisplay]) so the local PiP preview shows the same frames the encoder
 * consumes; CameraX serves both previews from one camera.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onError: (Throwable) -> Unit = {},
) : LocalVideo {

    private var camera: Camera? = null
    private val useCases = mutableListOf<UseCase>()
    private var encoderSurface: Surface? = null
    private var displaySurface: Surface? = null
    private var cameraSelector: CameraSelector? = null
    private var provider: ProcessCameraProvider? = null
    private val _stats = MutableStateFlow(VideoStats())

    override fun setEnabled(on: Boolean) {
        if (on) {
            val surface = encoderSurface ?: return
            if (camera == null) bindTo(surface)
        } else {
            unbind()
        }
    }

    override fun switchCamera() {
        val surface = encoderSurface ?: return
        val current = cameraSelector?.lensFacing ?: CameraSelector.LENS_FACING_FRONT
        cameraSelector = CameraSelector.Builder()
            .requireLensFacing(
                if (current == CameraSelector.LENS_FACING_FRONT) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                },
            )
            .build()
        unbind()
        bindTo(surface)
    }

    override fun onPreviewSurfaceLost() {
        unbind()
    }

    override fun attachDisplay(surface: Surface) {
        displaySurface = surface
        if (camera != null) rebind()
    }

    override fun detachDisplay() {
        displaySurface = null
        if (camera != null) rebind()
    }

    override val stats: StateFlow<VideoStats> get() = _stats

    /** Binds the camera to the encoder surface. Errors are delivered to [onError]. */
    fun bindTo(encoderSurface: Surface) {
        this.encoderSurface = encoderSurface
        bind()
    }

    private fun rebind() {
        unbind() // clears the camera; bind() restores it with the current use cases
        bind()
    }

    /** Binds the camera: the encoder Preview always; the display Preview when attached. */
    private fun bind() {
        val encoderSurface = encoderSurface ?: return
        val encoderPreview = Preview.Builder().build()
        encoderPreview.setSurfaceProvider { request -> provide(request) { encoderSurface } }
        val cases = mutableListOf<UseCase>(encoderPreview)

        displaySurface?.let { attached ->
            val displayPreview = Preview.Builder().build()
            displayPreview.setSurfaceProvider { request -> provide(request) { attached } }
            cases += displayPreview
        }

        val selector = cameraSelector ?: CameraSelector.DEFAULT_FRONT_CAMERA
        var boundCamera: Camera? = null
        var failed = false
        runBlocking {
            try {
                val provider = ProcessCameraProvider.Companion.awaitInstance(context)
                this@CameraController.provider = provider
                boundCamera = provider.bindToLifecycle(lifecycleOwner, selector, *cases.toTypedArray())
            } catch (t: Throwable) {
                onError(t)
                failed = true
            }
        }
        if (!failed) {
            this.camera = boundCamera
            synchronized(useCases) {
                useCases.clear()
                useCases += cases
            }
        }
    }

    /**
     * Answers a [SurfaceRequest] with the surface the provider captured at bind
     * time; a null target or the request refusing a surface is a quiet no-op.
     */
    private fun provide(request: SurfaceRequest, target: () -> Surface?) {
        val surface = target()
        if (surface == null || request.willNotProvideSurface()) return
        request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { }
    }

    /** Unbinds the camera. Must run on the main thread. */
    fun unbind() {
        val provider = provider
        if (provider != null) {
            runCatching { provider.unbindAll() }
        }
        camera = null
        useCases.clear()
    }
}
