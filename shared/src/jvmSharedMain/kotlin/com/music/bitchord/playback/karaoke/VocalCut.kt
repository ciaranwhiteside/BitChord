package com.music.bitchord.playback.karaoke

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns the lead vocal down in a stream of interleaved PCM, frame by frame, from the
 * open-unmix vocal mask: each bin keeps `1 - (1 - level) * mask` of itself.
 *
 * The STFT is the one the model's front end uses (`native/analyzer/vocal_spectrogram.cpp`):
 * a periodic Hann of [FFT] samples every [HOP], frame `f` centred on sample `f * HOP`, so a
 * mask frame lines up with the frame it was computed from. Resynthesis windows again and
 * overlap-adds; four Hann² a quarter-frame apart sum to exactly 1.5, which is divided out.
 *
 * Output is the input [LATENCY] samples later. Wherever the last four frames were all left
 * untouched (no mask, or the level at 1) the output is that delayed input itself rather
 * than its resynthesis, so Karaoke at 100% is the original audio bit for bit.
 *
 * The web player's worklet runs a TypeScript copy of this; both are held to
 * `web/fixtures/karaoke-vectors.json`.
 */
class VocalCut(private val channels: Int = 2) {
    /** The slider: 1 leaves the vocal alone, 0 takes all of it the mask finds. */
    var level = 1f

    /** The level actually in use, gliding toward [level] a frame at a time. */
    var applied = 1f
        private set

    private var position = 0L
    private var filled = 0
    private var cutFrames = 0
    private val history = Array(channels) { FloatArray(FFT) }
    private val sum = Array(channels) { FloatArray(FFT) }
    private val ready = Array(channels) { FloatArray(HOP) }
    private val re = FloatArray(FFT)
    private val im = FloatArray(FFT)
    private val mask = FloatArray(BINS)

    /** Forgets everything; the next sample in is track sample [startSample] (after a seek). */
    fun reset(startSample: Long = 0) {
        position = startSample
        filled = 0
        cutFrames = 0
        applied = level
        for (c in 0 until channels) {
            history[c].fill(0f)
            sum[c].fill(0f)
            ready[c].fill(0f)
        }
    }

    /**
     * Processes [frames] interleaved frames from [input] into [output]. [maskAt] fills one
     * channel's [BINS] mask values for the frame centred on a track sample, or returns false
     * when it has none for it yet; that frame then passes untouched.
     */
    fun process(
        input: FloatArray,
        frames: Int,
        output: FloatArray,
        maskAt: (centreSample: Long, channel: Int, into: FloatArray) -> Boolean,
    ) {
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                history[c][FFT - HOP + filled] = input[i * channels + c]
                output[i * channels + c] = ready[c][filled]
            }
            if (++filled == HOP) {
                filled = 0
                position += HOP
                frame(maskAt)
            }
        }
    }

    private fun frame(maskAt: (Long, Int, FloatArray) -> Boolean) {
        val step = level - applied
        applied = if (abs(step) < SETTLED) level else applied + step * GLIDE
        val centre = position - FFT / 2
        var cut = false
        for (c in 0 until channels) {
            val x = history[c]
            val acc = sum[c]
            if (applied < 1f && maskAt(centre, c, mask)) {
                cut = true
                for (t in 0 until FFT) {
                    re[t] = x[t] * WINDOW[t]
                    im[t] = 0f
                }
                fft(re, im, inverse = false)
                for (k in 0 until BINS) {
                    val keep = 1f - (1f - applied) * mask[k]
                    re[k] *= keep
                    im[k] *= keep
                }
                for (k in 1 until FFT / 2) {
                    re[FFT - k] = re[k]
                    im[FFT - k] = -im[k]
                }
                fft(re, im, inverse = true)
                for (t in 0 until FFT) acc[t] += re[t] * WINDOW[t] / OVERLAP
            } else {
                for (t in 0 until FFT) acc[t] += x[t] * WINDOW[t] * WINDOW[t] / OVERLAP
            }
        }
        if (cut) cutFrames = FFT / HOP
        for (c in 0 until channels) {
            // The block leaving now is covered by this frame and the three before it.
            (if (cutFrames > 0) sum[c] else history[c]).copyInto(ready[c], 0, 0, HOP)
            sum[c].copyInto(sum[c], 0, HOP, FFT)
            sum[c].fill(0f, FFT - HOP, FFT)
            history[c].copyInto(history[c], 0, HOP, FFT)
        }
        if (cutFrames > 0) cutFrames--
    }

    companion object {
        const val FFT = 4096
        const val HOP = 1024
        const val BINS = FFT / 2 + 1

        /** Samples between a sample going in and the same sample coming out. */
        const val LATENCY = FFT

        private const val OVERLAP = 1.5f
        private const val GLIDE = 0.5f
        private const val SETTLED = 0.01f

        private val WINDOW = FloatArray(FFT) { (0.5 - 0.5 * cos(2 * PI * it / FFT)).toFloat() }
        private val COS = FloatArray(FFT / 2) { cos(2 * PI * it / FFT).toFloat() }
        private val SIN = FloatArray(FFT / 2) { sin(2 * PI * it / FFT).toFloat() }
        private val REVERSED = IntArray(FFT).also { table ->
            var j = 0
            for (i in 1 until FFT) {
                var bit = FFT shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                table[i] = j
            }
        }

        /** The model's vocal estimate as a share of the mix in one bin. */
        fun maskOf(estimate: Float, mixture: Float): Float =
            if (mixture > 1e-9f) (estimate / mixture).coerceIn(0f, 1f) else 0f

        /** In-place radix-2 FFT of [FFT] points; the inverse is scaled by 1/N. */
        private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
            for (i in 0 until FFT) {
                val j = REVERSED[i]
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            val sign = if (inverse) 1f else -1f
            var length = 2
            while (length <= FFT) {
                val half = length / 2
                val stride = FFT / length
                var start = 0
                while (start < FFT) {
                    for (k in 0 until half) {
                        val wr = COS[k * stride]
                        val wi = sign * SIN[k * stride]
                        val a = start + k
                        val b = a + half
                        val tr = re[b] * wr - im[b] * wi
                        val ti = re[b] * wi + im[b] * wr
                        re[b] = re[a] - tr
                        im[b] = im[a] - ti
                        re[a] += tr
                        im[a] += ti
                    }
                    start += length
                }
                length = length shl 1
            }
            if (inverse) for (i in 0 until FFT) {
                re[i] /= FFT
                im[i] /= FFT
            }
        }
    }
}
