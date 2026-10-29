package io.streamrtc.api

interface RtcLogger {
    fun d(tag: String, message: String)
    fun e(tag: String, message: String, error: Throwable? = null)

    companion object {
        val None: RtcLogger = object : RtcLogger {
            override fun d(tag: String, message: String) = Unit
            override fun e(tag: String, message: String, error: Throwable?) = Unit
        }
    }
}
