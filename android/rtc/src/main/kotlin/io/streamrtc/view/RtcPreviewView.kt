package io.streamrtc.view

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceView
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import io.streamrtc.api.LocalVideo
/**
 * CameraX preview surface backed by a [SurfaceView]: frames from the camera are
 * handed to the attached [LocalVideo] through a [Surface] via the
 * [Preview.SurfaceProvider] contract.
 */
class RtcPreviewView(context: Context) : SurfaceView(context), Preview.SurfaceProvider {

    private val surface: Surface = Surface(SurfaceTexture(false))
    private var local: LocalVideo? = null

    /** Registers the [LocalVideo] that consumes preview frames. */
    fun setSource(local: LocalVideo?) {
        this.local = local
    }

    override fun onDetachedFromWindow() {
        local?.onPreviewSurfaceLost()
        super.onDetachedFromWindow()
    }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        val local = local
        if (local == null || request.willNotProvideSurface()) return
        request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { }
    }
}
