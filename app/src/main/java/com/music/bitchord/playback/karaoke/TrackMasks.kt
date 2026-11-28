package com.music.bitchord.playback.karaoke

import com.music.bitchord.playback.KaraokeMaskSource
import com.music.bitchord.playback.smart.VocalTracker
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One track's vocal masks, worked out a model window at a time just ahead of where the
 * sink is asking, and let go of once it has passed them.
 *
 * Window `k` covers [STEP_SECONDS]` * k` for ~22.3 s (the model's fixed width), so each
 * overlaps the next by ~2.3 s. The edges of a window are reflection-padded and the least
 * trustworthy part of its mask, so a frame reads the earlier window until [EDGE_SECONDS]
 * into the overlap and the later one after.
 *
 * [step] does one unit of work (or none) and is driven by [KaraokeMasks] on its single
 * inference thread; [maskAt] is called from the audio thread.
 */
class TrackMasks(private val compute: (index: Int) -> Result) : KaraokeMaskSource {

    class Window(val startSeconds: Double, val mask: VocalTracker.VocalMask) {
        val endSeconds: Double get() = startSeconds + mask.frames / MODEL_FPS
    }

    sealed interface Result {
        class Ready(val window: Window) : Result
        /** The audio for it isn't on disk yet. */
        data object NotYet : Result
        data object PastEnd : Result
        data object Failed : Result
    }

    private val windows = ConcurrentHashMap<Int, Window>()
    @Volatile private var wantedSeconds = 0.0
    @Volatile private var lastIndex = Int.MAX_VALUE

    @Volatile var failed = false
        private set

    /** Nothing more to compute: every window to the end of the track is done. */
    val ended: Boolean get() = neededIndices().none { it <= lastIndex && !windows.containsKey(it) }

    /** Where the player is, from the service, so windows are worked out ahead of it before the sink asks. */
    fun playheadAt(seconds: Double) {
        wantedSeconds = seconds
    }

    /** Whether the place the sink is at has its mask. */
    val ready: Boolean get() = windowFor(wantedSeconds) != null

    /** One window computed, or false when there was nothing to do (or nothing yet possible). */
    fun step(): Boolean {
        if (failed) return false
        val needed = neededIndices()
        windows.keys.filter { it < needed.first }.forEach(windows::remove)
        val index = needed.firstOrNull { it <= lastIndex && !windows.containsKey(it) } ?: return false
        return when (val result = compute(index)) {
            is Result.Ready -> { windows[index] = result.window; true }
            Result.NotYet -> false
            Result.PastEnd -> { lastIndex = index - 1; false }
            Result.Failed -> { failed = true; false }
        }
    }

    override fun maskAt(trackSample: Long, sampleRate: Int, channel: Int, into: FloatArray): Boolean {
        val seconds = trackSample.toDouble() / sampleRate
        wantedSeconds = seconds
        val window = windowFor(seconds) ?: return false
        val mask = window.mask
        val frame = ((seconds - window.startSeconds) * MODEL_FPS).roundToInt()
        if (frame !in 0 until mask.frames) return false
        val scale = sampleRate / MODEL_RATE
        for (bin in into.indices) {
            into[bin] = mask.at(channel, (bin * scale + 0.5).toInt().coerceAtMost(mask.bins - 1), frame)
        }
        return true
    }

    /** The window under the sink and the one after it. */
    private fun neededIndices(): IntRange {
        val first = floor(wantedSeconds / STEP_SECONDS).toInt().let { k ->
            if (k > 0 && wantedSeconds - k * STEP_SECONDS < EDGE_SECONDS) k - 1 else k
        }
        return first..first + 1
    }

    private fun windowFor(seconds: Double): Window? {
        val k = floor(seconds / STEP_SECONDS).toInt()
        val own = windows[k]?.takeIf { seconds >= it.startSeconds && seconds < it.endSeconds }
        val earlier = windows[k - 1]?.takeIf { seconds >= it.startSeconds && seconds < it.endSeconds - EDGE_SECONDS }
        return if (own != null && (seconds - own.startSeconds >= EDGE_SECONDS || earlier == null)) own else earlier ?: own
    }

    companion object {
        const val STEP_SECONDS = 20.0
        const val EDGE_SECONDS = 1.0
        const val MODEL_RATE = 44_100.0
        const val MODEL_FPS = MODEL_RATE / VocalCut.HOP
    }
}
