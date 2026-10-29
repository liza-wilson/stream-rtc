package io.streamrtc.media.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRouteSelectionTest {
    @Test
    fun selectedCommunicationDeviceWinsOverConnectedHeadset() {
        assertEquals(
            AudioRoute.Earpiece,
            selectAudioRoute(
                outputTypes = listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                ),
                selectedType = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                speakerphone = false,
            ),
        )
    }

    @Test
    fun selectedSpeakerReportsSpeaker() {
        assertEquals(
            AudioRoute.Speaker,
            selectAudioRoute(
                outputTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
                selectedType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                speakerphone = true,
            ),
        )
    }

    @Test
    fun bluetoothWinsScanWhenNothingIsSelected() {
        assertEquals(
            AudioRoute.Bluetooth,
            selectAudioRoute(
                outputTypes = listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                ),
                selectedType = null,
                speakerphone = false,
            ),
        )
    }

    @Test
    fun wiredHeadsetBeatsBuiltinFallback() {
        assertEquals(
            AudioRoute.Headset,
            selectAudioRoute(
                outputTypes = listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                ),
                selectedType = null,
                speakerphone = false,
            ),
        )
    }

    @Test
    fun speakerphoneFlagsSpeaksWhenOnlyBuiltinsArePresent() {
        assertEquals(
            AudioRoute.Speaker,
            selectAudioRoute(
                outputTypes = listOf(
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                ),
                selectedType = null,
                speakerphone = true,
            ),
        )
        assertEquals(
            AudioRoute.Earpiece,
            selectAudioRoute(
                outputTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
                selectedType = null,
                speakerphone = false,
            ),
        )
    }
}
