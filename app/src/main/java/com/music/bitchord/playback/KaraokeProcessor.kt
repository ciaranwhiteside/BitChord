package com.music.bitchord.playback

import androidx.media3.common.C
import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.karaoke.VocalCut

/** One track's vocal masks; see [com.music.bitchord.playback.karaoke.KaraokeMasks]. */
fun interface KaraokeMaskSource {
    /**
     * Fills [into] with [VocalCut.BINS] mask values for [channel] of the frame centred on
     * [trackSample] of a [sampleRate] stream, or returns false while that part isn't ready.
     */
    fun maskAt(trackSample: Long, sampleRate: Int, channel: Int, into: FloatArray): Boolean
}

/**
 * Karaoke's stage of the [com.music.bitchord.playback.audio.DspChain]: the vocal turned down
 * by its mask ([VocalCut]), first in the chain so the cut works on the track as decoded.
 *
 * While [enabled] the output is the input [latencyUs] later, masks or none, so a mask
 * arriving (or the slider moving) never shifts the audio; the sink takes the same amount
 * off the position it reports, which keeps the lyrics on what is heard. Turning it on or
 * off moves the audio by that much once. Stereo only: a mono stream has no centre to find.
 *
 * Masks are looked up by track sample, from the track time the sink sets in [blockTrackUs]
 * before each block. A gapless change reaches this sink before the service hears of it, so
 * [onStreamBoundary] moves to the `upcoming` source until [setSources] catches up.
 */
class KaraokeProcessor {
    @Volatile var enabled = false

    /** The slider: 1 is the original vocal, 0 as much of it out as the mask finds. */
    @Volatile var level = 1f

    /** Track time of the next block, in µs, or [C.TIME_UNSET] when the sink can't say. */
    var blockTrackUs = C.TIME_UNSET

    @Volatile private var current: KaraokeMaskSource? = null
    @Volatile private var upcoming: KaraokeMaskSource? = null
    @Volatile private var pastBoundary = false

    private var sampleRate = 0
    private var channels = 0
    private var cut: VocalCut? = null
    private var fed = 0L
    private var trackOffset = 0L
    private var scratch = FloatArray(0)

    /** How far behind the input the output runs; 0 while not cutting. */
    val latencyUs: Long
        get() = if (cut != null && sampleRate > 0) VocalCut.LATENCY * 1_000_000L / sampleRate else 0L

    /** The playing track's masks and the next one's. Called on every track change. */
    fun setSources(current: KaraokeMaskSource?, upcoming: KaraokeMaskSource?) {
        this.current = current
        this.upcoming = upcoming
        pastBoundary = false
    }

    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        channels = channelCount
        cut = null
    }

    fun process(block: AudioBlock) {
        val frames = block.frameCount
        if (!enabled || channels != 2 || sampleRate <= 0 || frames == 0) {
            cut = null
            return
        }
        val active = cut ?: VocalCut().also {
            cut = it
            fed = 0
        }
        active.level = level
        // Rounded: block times are whole µs, and truncating would jitter the mapping by a sample.
        if (blockTrackUs != C.TIME_UNSET) trackOffset = (blockTrackUs * sampleRate + 500_000L) / 1_000_000L - fed
        if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
        block.samples.copyInto(scratch, 0, 0, frames * 2)
        val source = if (pastBoundary) upcoming else current
        val offset = trackOffset
        active.process(scratch, frames, block.samples) { centre, channel, into ->
            val sample = centre + offset
            source != null && sample >= 0 && source.maskAt(sample, sampleRate, channel, into)
        }
        fed += frames
    }

    /** A new track has begun gaplessly on this sink. */
    fun onStreamBoundary() {
        pastBoundary = true
    }

    /** A seek or a skip: the audio starts again, and the service's current track is the one playing. */
    fun flush() {
        cut?.reset()
        fed = 0
        pastBoundary = false
    }

    fun reset() {
        cut = null
        sampleRate = 0
        channels = 0
    }
}
