package com.music.bitchord.playback.karaoke

import com.music.bitchord.playback.karaoke.VocalCut.Companion.HOP
import com.music.bitchord.playback.karaoke.VocalCut.Companion.LATENCY
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VocalCutTest {
    private val rate = 44_100.0

    /** Interleaved stereo: a low tone and a high one, the channels a little apart. */
    private fun signal(frames: Int) = FloatArray(frames * 2) { i ->
        val t = i / 2
        val c = i % 2
        (0.5 * sin(2 * PI * 220 * t / rate) + 0.3 * sin(2 * PI * 3_000 * t / rate + c)).toFloat()
    }

    private fun run(
        cut: VocalCut,
        input: FloatArray,
        chunk: Int = 4_410,
        mask: (Long, Int, FloatArray) -> Boolean,
    ): FloatArray {
        val out = FloatArray(input.size)
        var at = 0
        while (at < input.size / 2) {
            val n = minOf(chunk, input.size / 2 - at)
            val inChunk = input.copyOfRange(at * 2, (at + n) * 2)
            val outChunk = FloatArray(n * 2)
            cut.process(inChunk, n, outChunk, mask)
            outChunk.copyInto(out, at * 2)
            at += n
        }
        return out
    }

    private fun delayedError(input: FloatArray, out: FloatArray): Double {
        var worst = 0.0
        for (i in LATENCY * 2 until out.size) worst = maxOf(worst, abs((out[i] - input[i - LATENCY * 2]).toDouble()))
        return worst
    }

    private fun rms(values: FloatArray, from: Int): Double =
        sqrt((from until values.size).sumOf { values[it].toDouble() * values[it] } / (values.size - from))

    @Test
    fun `no mask passes the input through exactly, a fixed latency later`() {
        val input = signal(LATENCY * 4)
        val out = run(VocalCut().apply { level = 0f }, input) { _, _, _ -> false }
        assertEquals(0.0, delayedError(input, out))
    }

    @Test
    fun `full level is exact even with a mask`() {
        val input = signal(LATENCY * 4)
        val out = run(VocalCut(), input) { _, _, into -> into.fill(1f); true }
        assertEquals(0.0, delayedError(input, out))
    }

    @Test
    fun `an empty mask resynthesises the input to under -80 dB, whatever the chunking`() {
        val input = signal(LATENCY * 4)
        val out = run(VocalCut().apply { level = 0f }, input, chunk = 37) { _, _, into -> into.fill(0f); true }
        assertTrue(delayedError(input, out) < 1e-4, "error ${delayedError(input, out)}")
    }

    @Test
    fun `a full mask at level zero is silence once the level has settled`() {
        val input = signal(LATENCY * 6)
        val out = run(VocalCut().apply { level = 0f }, input) { _, _, into -> into.fill(1f); true }
        assertTrue(rms(out, LATENCY * 2 * 3) < 1e-5)
    }

    @Test
    fun `the level glides rather than steps, and arrives within eight hops`() {
        val input = signal(LATENCY * 6)
        val cut = VocalCut()
        val out = FloatArray(input.size)
        val block = FloatArray(HOP * 2)
        val levels = mutableListOf<Float>()
        for (hop in 0 until input.size / 2 / HOP) {
            if (hop == 8) cut.level = 0f
            cut.process(input.copyOfRange(hop * HOP * 2, (hop + 1) * HOP * 2), HOP, block) { _, _, into ->
                into.fill(1f); true
            }
            block.copyInto(out, hop * HOP * 2)
            levels += cut.applied
        }
        val steps = levels.zipWithNext { a, b -> abs(a - b) }
        assertTrue(steps.max() < 0.6f, "steps $steps")
        assertEquals(0f, levels[8 + 8])
    }

    @Test
    fun `a low-band mask takes the low tone out and leaves the high one`() {
        val input = signal(LATENCY * 6)
        // Bins under ~750 Hz are "voice" here.
        val out = run(VocalCut().apply { level = 0f }, input) { _, _, into ->
            for (k in into.indices) into[k] = if (k < 70) 1f else 0f
            true
        }
        val from = LATENCY * 2 * 3
        val high = FloatArray(out.size) { i ->
            val t = i / 2 - LATENCY
            (0.3 * sin(2 * PI * 3_000 * t / rate + i % 2)).toFloat()
        }
        val residual = FloatArray(out.size) { out[it] - high[it] }
        assertTrue(rms(residual, from) < 0.01 * rms(high, from), "residual ${rms(residual, from)}")
    }

    @Test
    fun `mask comes from the estimate over the mixture, clamped`() {
        assertEquals(0.5f, VocalCut.maskOf(1f, 2f))
        assertEquals(1f, VocalCut.maskOf(3f, 2f))
        assertEquals(0f, VocalCut.maskOf(1f, 0f))
    }

    @Test
    fun `asks for each frame by the track sample it is centred on`() {
        val centres = mutableListOf<Long>()
        val cut = VocalCut().apply { level = 0f; reset(startSample = 44_100) }
        run(cut, signal(HOP * 3)) { centre, channel, _ -> if (channel == 0) centres += centre; false }
        assertEquals(listOf(44_100L + HOP - 2_048, 44_100L + 2 * HOP - 2_048, 44_100L + 3 * HOP - 2_048), centres)
    }

    /**
     * The web worklet runs a TypeScript copy of this; both check these numbers. Set
     * WRITE_KARAOKE_VECTORS to rewrite them after a deliberate change here.
     */
    @Test
    fun `matches the shared test vectors`() {
        val input = signal(LATENCY * 3)
        val out = run(VocalCut().apply { level = 0.25f }, input) { _, _, into ->
            for (k in into.indices) into[k] = if (k < 70) 0.8f else 0.1f
            true
        }
        val picks = (0 until 24).map { LATENCY * 2 + it * 311 }
        val file = File("../web/fixtures/karaoke-vectors.json")
        if (System.getenv("WRITE_KARAOKE_VECTORS") != null) {
            file.writeText(
                "{\n  \"level\": 0.25,\n  \"frames\": ${LATENCY * 3},\n  \"indices\": $picks,\n" +
                    "  \"values\": ${picks.map { out[it] }}\n}\n",
            )
        }
        val expected = Regex("\"values\": \\[([^]]*)]").find(file.readText())!!.groupValues[1]
            .split(",").map { it.trim().toFloat() }
        picks.forEachIndexed { n, i -> assertEquals(expected[n], out[i], 1e-5f) }
    }
}
