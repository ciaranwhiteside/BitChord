package com.music.bitchord.playback.karaoke

import com.music.bitchord.playback.karaoke.TrackMasks.Companion.EDGE_SECONDS
import com.music.bitchord.playback.karaoke.TrackMasks.Companion.STEP_SECONDS
import com.music.bitchord.playback.karaoke.TrackMasks.Window
import com.music.bitchord.playback.smart.VocalTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMasksTest {
    private val bins = VocalCut.BINS
    private val frames = 960

    /** A window whose every cell holds [value], tagging which window answered. */
    private fun window(index: Int, value: Int = index + 1, start: Double = index * STEP_SECONDS) =
        Window(start, VocalTracker.VocalMask(ByteArray(2 * bins * frames) { value.toByte() }, frames, bins))

    private fun answer(masks: TrackMasks, seconds: Double, rate: Int = 44_100): Int? {
        val into = FloatArray(VocalCut.BINS)
        if (!masks.maskAt((seconds * rate).toLong(), rate, 0, into)) return null
        return (into[0] * 255 + 0.5f).toInt()
    }

    @Test
    fun `works out the window the sink is in, then the one after, and nothing past the end`() {
        val asked = mutableListOf<Int>()
        val masks = TrackMasks { index -> asked += index; if (index < 3) TrackMasks.Result.Ready(window(index)) else TrackMasks.Result.PastEnd }
        repeat(6) { masks.step() }
        assertEquals(listOf(0, 1), asked)
        masks.maskAt((25.0 * 44_100).toLong(), 44_100, 0, FloatArray(bins))
        repeat(4) { masks.step() }
        assertEquals(listOf(0, 1, 2), asked)
        masks.maskAt((45.0 * 44_100).toLong(), 44_100, 0, FloatArray(bins))
        repeat(4) { masks.step() }
        assertEquals(listOf(0, 1, 2, 3), asked)
        assertTrue(masks.ended)
    }

    @Test
    fun `reads a window's middle and hands over a second into the overlap`() {
        val masks = TrackMasks { index -> TrackMasks.Result.Ready(window(index)) }
        repeat(2) { masks.step() }
        assertEquals(1, answer(masks, 5.0))
        assertEquals(1, answer(masks, STEP_SECONDS + EDGE_SECONDS - 0.1))
        assertEquals(2, answer(masks, STEP_SECONDS + EDGE_SECONDS + 0.1))
    }

    @Test
    fun `lets go of windows behind the sink`() {
        val masks = TrackMasks { index -> TrackMasks.Result.Ready(window(index)) }
        repeat(2) { masks.step() }
        masks.maskAt((45.0 * 44_100).toLong(), 44_100, 0, FloatArray(bins))
        repeat(3) { masks.step() }
        assertEquals(null, answer(masks, 5.0))
        assertEquals(3, answer(masks, 45.0))
    }

    @Test
    fun `waits for audio not on disk yet, and gives up on a model that fails`() {
        var cached = false
        val masks = TrackMasks { index ->
            if (!cached) TrackMasks.Result.NotYet else TrackMasks.Result.Ready(window(index))
        }
        masks.step()
        assertFalse(masks.ready)
        cached = true
        masks.step()
        assertTrue(masks.ready)

        val broken = TrackMasks { TrackMasks.Result.Failed }
        broken.step()
        assertTrue(broken.failed)
    }

    @Test
    fun `follows a window's real start and maps bins across sample rates`() {
        // The decoder landed 0.5 s early; frame 0 is at 0.5 s of track time.
        val cells = ByteArray(2 * bins * frames).also { m ->
            for (b in 0 until bins) m[b * frames + 0] = (b % 200).toByte()
        }
        val masks = TrackMasks { TrackMasks.Result.Ready(Window(0.5, VocalTracker.VocalMask(cells, frames, bins))) }
        masks.step()
        val into = FloatArray(bins)
        assertTrue(masks.maskAt(24_000, 48_000, 0, into))
        // Sink bin 1000 at 48 kHz is model bin round(1000 * 48000 / 44100) = 1088.
        assertEquals(1088 % 200 / 255f, into[1000], 1e-6f)
    }
}
