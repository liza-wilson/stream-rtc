package io.streamrtc.view

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.streamrtc.api.LocalVideo

/**
 * Local camera PiP preview backed by a [SurfaceView]: the holder surface is
 * handed to the attached [LocalVideo] (display preview output) when it is
 * created and revoked on destroy.
 */
class RtcPreviewView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private var local: LocalVideo? = null
    private var displayAttached = false

    init {
        holder.addCallback(this)
        setZOrderMediaOverlay(true)
    }

    /** Registers the [LocalVideo] that consumes preview frames. */
    fun setSource(local: LocalVideo?) {
        this.local = local
        maybeAttach()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = maybeAttach()

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = maybeAttach()

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        local?.detachDisplay()
        displayAttached = false
    }

    override fun onDetachedFromWindow() {
        local?.detachDisplay()
        displayAttached = false
        super.onDetachedFromWindow()
    }

    private fun maybeAttach() {
        val local = local ?: return
        val surface = holder.surface ?: return
        if (!surface.isValid) return
        if (displayAttached) return
        local.attachDisplay(surface)
        displayAttached = true
    }
}
