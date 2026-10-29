package io.streamrtc.view

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.streamrtc.api.RemoteVideo

/**
 * A [SurfaceView] that hosts a [RemoteVideo]: attaches on surface creation and
 * detaches on destruction. [setSource] swaps the hosted video.
 */
class RtcRemoteVideoView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private var source: RemoteVideo? = null

    init { holder.addCallback(this) }

    fun setSource(remote: RemoteVideo?) {
        val previous = source
        if (previous !== remote) {
            previous?.let { (it as? SurfaceSink)?.detach() }
        }
        source = remote
        if (remote != null && holder.surface != null && holder.surface.isValid) {
            (remote as? SurfaceSink)?.attach(holder.surface)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        (source as? SurfaceSink)?.attach(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        (source as? SurfaceSink)?.detach()
    }
}
