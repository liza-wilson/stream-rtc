package io.streamrtc.service

import android.content.Context
import io.streamrtc.util.AndroidLogger

/**
 * The foreground-service seam. [SessionManager] only ever sees this interface,
 * which keeps the session package free of Android types and JVM-testable.
 *
 * Implementations must be non-blocking: they are called from the session's
 * serial event path, never from a media thread.
 */
interface ServiceControl {
    /** Starts (or keeps) the call foreground service. */
    fun start()

    /** Stops the call foreground service and removes its notification. */
    fun stop()
}

/**
 * Real [ServiceControl] backed by [CallService]. Starting uses
 * `startForegroundService` on API 26+ so the service may outlive the host's
 * activity; stopping calls `stopService` directly, which runs the service's
 * `onDestroy` and removes the notification.
 */
class CallServiceControl(private val context: Context) : ServiceControl {
    private val logger = AndroidLogger()

    override fun start() {
        try {
            CallService.start(context)
        } catch (e: Exception) {
            // A background-start restriction, a missing permission, or a
            // vendor ROM quirk: media continues, only the notification is lost.
            logger.e(TAG, "could not start the call service", e)
        }
    }

    override fun stop() {
        try {
            CallService.stop(context)
        } catch (e: Exception) {
            logger.e(TAG, "could not stop the call service", e)
        }
    }

    private companion object {
        const val TAG = "CallServiceControl"
    }
}
