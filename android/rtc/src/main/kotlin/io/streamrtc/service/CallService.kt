package io.streamrtc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * The call foreground service: keeps the process alive while a call is in
 * progress and gives the user a way back and a way to hang up.
 *
 * It is binder-less by design. The session side installs the two callbacks in
 * [CallServiceHooks] once the SDK is started; the service never talks to a
 * manager object directly, which keeps the manager free of Android types and
 * avoids lifecycle coupling (the service may outlive a configuration change).
 *
 * Notification behaviour: one low-importance, silent, ongoing notification in
 * the `streamrtc_call` channel. On Android 13+ a denied
 * `POST_NOTIFICATIONS` does not throw — the service still runs in the
 * foreground and the notification is simply not shown.
 */
class CallService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> stopSelf()

                ACTION_END -> {
                    val end = CallServiceHooks.onEnd
                    if (end != null) {
                        end()
                    } else {
                        // No SDK behind the service (process recreated after death):
                        // the SDK never resumes a call by itself, so clean up.
                        stopSelf()
                    }
                }

                ACTION_TOGGLE_MUTE -> CallServiceHooks.onToggleMute?.invoke()

                else -> enterForeground()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun enterForeground() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Both camera and microphone: the service keeps a 1:1 call's
                // video and audio alive across activity changes on API 29+.
                startForeground(NOTIFICATION_ID, notification, FOREGROUND_TYPES)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Background-start restrictions and vendor ROMs can reject the
            // promotion; do not take the process down with it.
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(this, REQUEST_OPEN, launch, PENDING_FLAGS)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Ongoing call")
            .setContentText("Tap to return to the call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Mute", serviceIntent(ACTION_TOGGLE_MUTE, REQUEST_MUTE))
            .addAction(0, "End", serviceIntent(ACTION_END, REQUEST_END))
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        return builder.build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, CallService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, PENDING_FLAGS)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (channelCreated) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ongoing call",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a StreamRTC call is in progress"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        channelCreated = true
    }

    companion object {
        const val ACTION_START = "io.streamrtc.action.START_CALL"
        const val ACTION_STOP = "io.streamrtc.action.STOP_CALL"
        const val ACTION_END = "io.streamrtc.action.END_CALL"
        const val ACTION_TOGGLE_MUTE = "io.streamrtc.action.TOGGLE_MUTE"

        private const val TAG = "CallService"
        private const val CHANNEL_ID = "streamrtc_call"
        private const val NOTIFICATION_ID = 0x5C
        private const val REQUEST_OPEN = 1
        private const val REQUEST_END = 2
        private const val REQUEST_MUTE = 3

        private const val PENDING_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private val FOREGROUND_TYPES =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        @Volatile
        private var channelCreated = false

        /** Starts the service; safe to call repeatedly. */
        internal fun start(context: Context) {
            try {
                val intent = Intent(context, CallService::class.java).setAction(ACTION_START)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed", e)
            }
        }

        /** Stops the service; safe even when it is not running. */
        internal fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}

/**
 * Static seam between [CallService] and the session manager.
 *
 * The manager is not an Android object, so the façade installs these callbacks
 * when the SDK starts and clears them when it stops. Actions arriving with no
 * callback (a recreated process) are ignored rather than resurrecting a call.
 */
object CallServiceHooks {
    @Volatile
    var onEnd: (() -> Unit)? = null

    @Volatile
    var onToggleMute: (() -> Unit)? = null
}
