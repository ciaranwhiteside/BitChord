package com.music.bitchord

import android.media.AudioDeviceInfo
import com.music.bitchord.playback.audio.LosslessOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LosslessOutputTest {

    @Test
    fun `a connected DAC does not make active A2DP lossless`() {
        val state = LosslessOutput.decide(
            outputTypes = setOf(
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            ),
            bluetoothCodec = "SBC",
            bluetoothSampleRateHz = 48_000,
            bluetoothActive = true,
        )
        assertFalse(state.capable)
        assertEquals("SBC", state.bluetoothCodec)
    }

    @Test
    fun `aptX Lossless on A2DP alone still counts when the active flag is unreliable`() {
        // isBluetoothA2dpOn() is deprecated and can report false while A2DP is
        // the only route; the codec must still decide.
        val state = LosslessOutput.decide(
            outputTypes = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            bluetoothCodec = "aptX Lossless",
            bluetoothSampleRateHz = 44_100,
            bluetoothActive = false,
        )
        assertTrue(state.capable)
        assertEquals(LosslessOutput.Kind.BLUETOOTH, state.via)
    }

    @Test
    fun `lossy A2DP alone is not lossless when the active flag is unreliable`() {
        val state = LosslessOutput.decide(
            outputTypes = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            bluetoothCodec = "AAC",
            bluetoothSampleRateHz = 48_000,
            bluetoothActive = false,
        )
        assertFalse(state.capable)
        assertEquals("AAC", state.bluetoothCodec)
    }

    @Test
    fun `wired output is lossless when Bluetooth is not active`() {
        val state = LosslessOutput.decide(
            outputTypes = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
            bluetoothCodec = null,
            bluetoothActive = false,
        )
        assertTrue(state.capable)
        assertEquals(LosslessOutput.Kind.WIRED, state.via)
    }

    @Test
    fun `high-bitrate codecs open the addon gate, but only aptX Lossless is bit-exact`() {
        for (codec in listOf("LDAC", "LHDC V5", "SSC UHQ", "aptX Lossless")) {
            assertTrue(codec, LosslessOutput.isLosslessBluetoothCodec(codec, 96_000))
        }
        assertFalse(LosslessOutput.isLosslessBluetoothCodec("AAC", 48_000))
        assertTrue(LosslessOutput.isBitExactBluetoothCodec("aptX Lossless"))
        for (codec in listOf("LDAC", "LHDC V5", "SSC UHQ")) {
            assertFalse(codec, LosslessOutput.isBitExactBluetoothCodec(codec))
        }
    }

    @Test
    fun `unknown active Bluetooth codec is reported, not guessed`() {
        val state = LosslessOutput.decide(
            outputTypes = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            bluetoothCodec = null,
            bluetoothActive = true,
        )
        assertFalse(state.capable)
        assertTrue(state.bluetoothCodecUnknown)
    }
}
