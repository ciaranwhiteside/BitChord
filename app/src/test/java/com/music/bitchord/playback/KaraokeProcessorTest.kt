package com.music.bitchord.playback

import androidx.media3.common.C
import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.karaoke.VocalCut
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class KaraokeProcessorTest {
    private val rate = 48_000

    private fun block(frames: Int, from: Int) = AudioBlock(channelCount = 2, capacityFrames = frames).apply {
        reset(frames)
        for (i in 0 until frames * 2) samples[i] = sin((from * 2 + i) * 0.01).toFloat()
    }

    /** Feeds [blocks] blocks of [frames], track time following on from [startUs]; returns all output. */
    private fun KaraokeProcessor.feed(blocks: Int, frames: Int = 1_000, startUs: Long = 0): FloatArray {
        val out = FloatArray(blocks * frames * 2)
        for (b in 0 until blocks) {
            val block = block(frames, b * frames)
            blockTrackUs = startUs + b * frames * 1_000_000L / rate
            process(block)
            block.samples.copyInto(out, b * frames * 2, 0, frames * 2)
        }
        return out
    }

    private fun input(blocks: Int, frames: Int = 1_000) =
        FloatArray(blocks * frames * 2) { sin(it * 0.01).toFloat() }

    @Test
    fun `off leaves the audio alone and adds no latency`() {
        val karaoke = KaraokeProcessor().apply { configure(rate, 2) }
        assertArrayEquals(input(3), karaoke.feed(3), 0f)
        assertEquals(0L, karaoke.latencyUs)
    }

    @Test
    fun `on without masks is the input exactly, a fixed latency later`() {
        val karaoke = KaraokeProcessor().apply { configure(rate, 2); enabled = true; level = 0f }
        val out = karaoke.feed(20)
        val expected = input(20)
        for (i in VocalCut.LATENCY * 2 until out.size) assertEquals(expected[i - VocalCut.LATENCY * 2], out[i], 0f)
        assertEquals(VocalCut.LATENCY * 1_000_000L / rate, karaoke.latencyUs)
    }

    @Test
    fun `asks the current track's masks by track sample`() {
        val asked = mutableListOf<Long>()
        val karaoke = KaraokeProcessor().apply {
            configure(rate, 2)
            enabled = true
            level = 0f
            setSources(current = { sample, sampleRate, _, _ -> asked += sample; assertEquals(rate, sampleRate); false }, upcoming = null)
        }
        karaoke.feed(10, startUs = 2_000_000)
        assertTrue(asked.isNotEmpty())
        // The first frame is centred half a window before the first full hop's end.
        assertEquals(2L * rate + VocalCut.HOP - VocalCut.FFT / 2, asked.first())
    }

    @Test
    fun `moves to the next track's masks at a gapless boundary, and back on the service's word`() {
        var current = 0
        var upcoming = 0
        val karaoke = KaraokeProcessor().apply {
            configure(rate, 2)
            enabled = true
            level = 0f
            setSources(current = { _, _, _, _ -> current++; false }, upcoming = { _, _, _, _ -> upcoming++; false })
        }
        karaoke.feed(10)
        karaoke.onStreamBoundary()
        val before = current
        karaoke.feed(10)
        assertEquals(before, current)
        assertTrue(upcoming > 0)
    }

    @Test
    fun `a mask turns the audio down and mono is left alone`() {
        val karaoke = KaraokeProcessor().apply {
            configure(rate, 2)
            enabled = true
            level = 0f
            setSources(current = { _, _, _, into -> into.fill(1f); true }, upcoming = null)
        }
        val out = karaoke.feed(40)
        val tail = out.copyOfRange(out.size / 2, out.size)
        assertTrue(tail.maxOf { kotlin.math.abs(it) } < 1e-4f)

        val mono = KaraokeProcessor().apply { configure(rate, 1); enabled = true }
        val block = AudioBlock(channelCount = 1, capacityFrames = 100).apply { reset(100); samples.fill(0.5f) }
        mono.process(block)
        assertTrue(block.samples.take(100).all { it == 0.5f })
        assertEquals(0L, mono.latencyUs)
    }

    @Test
    fun `turning off drops the delay at once`() {
        val karaoke = KaraokeProcessor().apply { configure(rate, 2); enabled = true }
        karaoke.feed(5)
        karaoke.enabled = false
        val block = block(1_000, 0)
        val copy = block.samples.copyOf()
        karaoke.blockTrackUs = C.TIME_UNSET
        karaoke.process(block)
        assertArrayEquals(copy, block.samples, 0f)
        assertFalse(karaoke.latencyUs > 0)
    }
}
