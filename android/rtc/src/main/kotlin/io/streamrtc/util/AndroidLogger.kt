package io.streamrtc.util

import android.util.Log
import io.streamrtc.api.RtcLogger

/**
 * [RtcLogger] backed by `android.util.Log`. The tag passed by the SDK is used
 * verbatim (`ControlClient`, `RelayClient`, ...), which keeps `logcat` filtering
 * by subsystem possible.
 */
class AndroidLogger : RtcLogger {
    override fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun e(tag: String, message: String, error: Throwable?) {
        Log.e(tag, message, error)
    }
}
