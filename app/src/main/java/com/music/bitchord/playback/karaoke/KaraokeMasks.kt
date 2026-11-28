package com.music.bitchord.playback.karaoke

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.util.Log
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.smart.AudioDecoder
import com.music.bitchord.playback.smart.AutomixModels
import com.music.bitchord.playback.smart.LocalAudioSource
import com.music.bitchord.playback.smart.MelSpectrogram
import com.music.bitchord.playback.smart.VocalTracker
import com.music.bitchord.playback.karaoke.TrackMasks.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [TrackMasks] for the playing track and the next one, their windows worked out on one
 * background thread while Karaoke is on.
 *
 * Read from the very copy the player is reading: the cached rendition its URI resolves to,
 * or the local file. Automix may measure a lighter sibling rendition, but a mask is only any
 * use lined up sample for sample with the audio it is applied to, and another encode (let
 * alone another cut) is not that. Only bytes already on disk are read; a window the player
 * hasn't downloaded yet waits for it.
 */
class KaraokeMasks(context: Context) {
    data class Item(val id: String, val uri: Uri, val durationSeconds: Double)

    private val resolver = context.applicationContext.contentResolver
    private val tracker = VocalTracker(
        modelPath = { AutomixModels.path(AutomixModels.VOCALS) },
        // One thread: the phone runs a window at 76x real time on it, and more were slower.
        inferenceThreads = { 1 },
    )
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var order: List<Pair<String, TrackMasks>> = emptyList()
    private var job: Job? = null

    /** True while the vocal model is still downloading. */
    @Volatile var awaitingModel = false
        private set

    /** Masks for [current] and [next], current worked first; any other track's are let go. */
    @Synchronized
    fun follow(current: Item?, next: Item?): Pair<TrackMasks?, TrackMasks?> {
        val held = order.toMap()
        order = listOfNotNull(current, next).distinctBy { it.id }.map { item ->
            item.id to (held[item.id] ?: TrackMasks { index -> window(item, index) })
        }
        if (job?.isActive != true) job = scope.launch {
            while (isActive) {
                val tracks = synchronized(this@KaraokeMasks) { order.map { it.second } }
                if (tracks.isEmpty()) break
                if (tracks.none { it.step() }) delay(IDLE_MS)
            }
        }
        val byId = order.toMap()
        return current?.let { byId[it.id] } to next?.let { byId[it.id] }
    }

    /** Karaoke is off: stop working and let every mask go. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        order = emptyList()
    }

    private fun window(item: Item, index: Int): Result {
        val start = index * TrackMasks.STEP_SECONDS
        if (item.durationSeconds > 0 && start >= item.durationSeconds) return Result.PastEnd
        if (runCatching { AutomixModels.path(AutomixModels.VOCALS) }.isFailure) {
            // path() has already asked for the download; Karaoke wants it now whatever Automix says.
            awaitingModel = true
            AutomixModels.download()
            return Result.NotYet
        }
        awaitingModel = false
        val copy = copyOf(item.uri) ?: return Result.NotYet
        val end = start + WINDOW_SECONDS
        val decoded = copy.open()?.use { AudioDecoder.decodeRegionStereo(it, start, end) }
            // A whole copy that won't decode here: past its end, unless it's the first window.
            ?: return if (!copy.complete) Result.NotYet else if (index > 0) Result.PastEnd else Result.Failed
        val (pcm, realStart) = decoded
        val left = MelSpectrogram.resample(pcm.left, pcm.sampleRate, TrackMasks.MODEL_RATE) ?: return Result.Failed
        val right = MelSpectrogram.resample(pcm.right, pcm.sampleRate, TrackMasks.MODEL_RATE) ?: return Result.Failed
        val wanted = ((minOf(end, item.durationSeconds.takeIf { it > 0 } ?: end) - realStart) *
            TrackMasks.MODEL_RATE).toInt()
        // Short of what was asked for with more still to download: the rest isn't on disk yet.
        if (!copy.complete && left.size < wanted * 0.98) return Result.NotYet
        val samples = minOf(left.size, WINDOW_SAMPLES)
        val mask = tracker.mask(left.copyOf(samples), right.copyOf(samples), TrackMasks.MODEL_RATE)
            ?: return Result.Failed.also { Log.w(TAG, "No mask for ${item.id} window $index") }
        return Result.Ready(TrackMasks.Window(realStart, mask))
    }

    private class Copy(val complete: Boolean, val open: () -> MediaDataSource?)

    private fun copyOf(uri: Uri): Copy? {
        if (LocalAudioSource.isLocal(uri)) return Copy(complete = true) { LocalAudioSource.open(resolver, uri) }
        val key = AudioCache.cacheKeyOf(uri)
        val rendition = AudioCache.renditionsOf(uri).firstOrNull { it.key == key } ?: return null
        return Copy(rendition.isComplete) { AudioCache.renditionDataSource(uri, rendition) }
    }

    companion object {
        private const val TAG = "KaraokeMasks"
        private const val IDLE_MS = 250L
        private const val WINDOW_SAMPLES = (VocalTracker.FIXED_FRAMES - 1) * VocalCut.HOP
        private const val WINDOW_SECONDS = WINDOW_SAMPLES / TrackMasks.MODEL_RATE
    }
}
