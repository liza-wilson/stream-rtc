package io.streamrtc.session

import android.hardware.camera2.CameraAccessException
import io.streamrtc.api.EndReason
import java.io.IOException

/**
 * Turns a platform or media failure into the typed reason a call ends with, so
 * a camera stolen by another app ends the call instead of crashing the host.
 *
 * The mapping is intentionally narrow: only failures the SDK can name are
 * classified, everything else is preserved as [EndReason.Error] with the
 * throwable's simple class name so the host can log or report it.
 */
object ErrorMapper {
    fun toEndReason(error: Throwable): EndReason = when {
        error is SecurityException -> EndReason.PermissionDenied
        error is CameraAccessException -> EndReason.PermissionDenied
        error is IllegalStateException && error.message.orEmpty().contains("camera", ignoreCase = true) ->
            EndReason.PermissionDenied

        error is IOException -> EndReason.NetworkFailed
        else -> EndReason.Error(error.javaClass.simpleName)
    }
}
