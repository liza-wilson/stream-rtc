package io.streamrtc.media.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the call's audio is currently routed to the user. */
enum class AudioRoute { Speaker, Earpiece, Headset, Bluetooth }

/**
 * Audio-focus changes the router reports to its owner. The owner decides what a
 * pause means for capture; [AudioRouter] itself never touches media streams.
 */
enum class AudioFocusState { Paused, Resumed, Lost }

/**
 * Owns the call's [AudioManager] configuration: communication mode, transient
 * audio focus, speakerphone selection and route tracking.
 *
 * [start] requests [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT] (a call may not
 * outrank music forever) and logs/bails out when the request is denied. While
 * running it observes [AudioDeviceCallback] and publishes the current
 * [AudioRoute] on [route].
 *
 * Focus transitions are surfaced through [onFocusChange] — `Paused` for
 * `LOSS_TRANSIENT`/`LOSS_TRANSIENT_CAN_DUCK`, `Resumed` for any gain, `Lost`
 * on `LOSS`. There is deliberately no media-stream logic here; [DefaultAudioDevice]
 * stops/restarts capture in response.
 *
 * Speaker selection uses `setCommunicationDevice` on API 31+ and the legacy
 * `isSpeakerphoneOn` flag below it, so the class loads and runs on API 24.
 */
class AudioRouter(context: Context) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _route = MutableStateFlow(currentRoute())
    val route: StateFlow<AudioRoute> = _route.asStateFlow()

    /** Internal seam used by [DefaultAudioDevice]; Task 12 need not touch it. */
    var onFocusChange: ((AudioFocusState) -> Unit)? = null

    private var started = false
    @Volatile
    private var focused = false
    private var speakerphone = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                focused = false
                onFocusChange?.invoke(AudioFocusState.Lost)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> onFocusChange?.invoke(AudioFocusState.Paused)

            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            -> {
                focused = true
                onFocusChange?.invoke(AudioFocusState.Resumed)
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshRoute()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshRoute()
    }

    /**
     * Enters communication mode, requests transient focus and applies
     * [speakerphone]. If focus is denied the router backs out, logs and leaves
     * [route] untouched. Idempotent.
     */
    @Synchronized
    fun start(speakerphone: Boolean) {
        if (started) {
            setSpeakerphone(speakerphone)
            return
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        started = true

        if (!requestFocus()) {
            Log.w(TAG, "audio focus request was not granted; leaving audio unmanaged")
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            audioManager.mode = AudioManager.MODE_NORMAL
            started = false
            return
        }
        focused = true

        setSpeakerphone(speakerphone)
        refreshRoute()
    }

    /** Abandons focus, unregisters callbacks and restores normal mode. Idempotent. */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        if (focused) {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
            focused = false
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    /** Routes to the loudspeaker when [on], otherwise to the earpiece. */
    @Synchronized
    fun setSpeakerphone(on: Boolean) {
        speakerphone = on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setCommunicationDevice(on)
        } else {
            setLegacySpeakerphone(on)
        }
        refreshRoute()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun setCommunicationDevice(speaker: Boolean) {
        val targetType = if (speaker) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }
        val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == targetType }
        if (device == null) {
            Log.w(TAG, "no communication device for type $targetType")
            return
        }
        if (!audioManager.setCommunicationDevice(device)) {
            Log.w(TAG, "setCommunicationDevice($targetType) was rejected")
        }
    }

    @Suppress("DEPRECATION")
    private fun setLegacySpeakerphone(on: Boolean) {
        audioManager.isSpeakerphoneOn = on
    }

    private fun refreshRoute() {
        _route.value = currentRoute()
    }

    private fun currentRoute(): AudioRoute {
        val devices = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrElse {
            Log.w(TAG, "could not enumerate output devices", it)
            emptyArray<AudioDeviceInfo>()
        }

        if (devices.any { it.type in BLUETOOTH_TYPES }) return AudioRoute.Bluetooth
        if (devices.any { it.type in HEADSET_TYPES }) return AudioRoute.Headset

        val speaker = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn
        }
        return if (speaker) AudioRoute.Speaker else AudioRoute.Earpiece
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean =
        audioManager.requestAudioFocus(
            focusListener,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private companion object {
        const val TAG = "AudioRouter"

        val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )

        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
        )
    }
}
