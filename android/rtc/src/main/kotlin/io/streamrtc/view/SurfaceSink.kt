package io.streamrtc.view

import android.view.Surface

/** A sink that can be attached to / detached from a rendering surface. */
interface SurfaceSink {
    fun attach(surface: Surface)
    fun detach()
}
