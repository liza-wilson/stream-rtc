package io.streamrtc.api

data class VideoConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val keyframeIntervalSec: Int = 2,
) {
    companion object {
        val Low = VideoConfig(320, 180, 15, 300_000)
        val Standard = VideoConfig(640, 360, 24, 700_000)
        val High = VideoConfig(1280, 720, 30, 1_500_000)
    }
}

data class AudioConfig(
    val sampleRate: Int = 16_000,
    val frameMs: Int = 20,
    val echoCancellation: Boolean = true,
    val noiseSuppression: Boolean = true,
) {
    companion object { val Default = AudioConfig() }
    /** Bytes in one mono PCM16 frame. */
    val frameBytes: Int get() = sampleRate / 1000 * frameMs * 2
}

data class CallMedia(val audio: Boolean, val video: Boolean) {
    companion object {
        val audioVideo = CallMedia(audio = true, video = true)
        val audioOnly = CallMedia(audio = true, video = false)
    }
}

data class StreamRtcConfig(
    val controlUrl: String,
    val tokenProvider: suspend () -> String,
    val displayName: String,
    val appVersion: String? = null,
    val roomTokenProvider: (suspend (String) -> String)? = null,
    val video: VideoConfig = VideoConfig.Standard,
    val audio: AudioConfig = AudioConfig.Default,
    val foregroundService: Boolean = true,
)
