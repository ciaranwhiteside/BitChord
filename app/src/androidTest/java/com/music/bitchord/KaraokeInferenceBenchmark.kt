package com.music.bitchord

import android.media.MediaDataSource
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.bitchord.playback.karaoke.VocalCut
import com.music.bitchord.playback.smart.AudioDecoder
import com.music.bitchord.playback.smart.AutomixModels
import com.music.bitchord.playback.smart.MelSpectrogram
import com.music.bitchord.playback.smart.VocalSpectrogram
import com.music.bitchord.playback.smart.VocalTracker
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Karaoke's perf gate (SPEC.md, Checkpoint A): how fast the open-unmix mask runs on this
 * phone, and what the cut sounds like across window seams. Not part of the unit suite.
 *
 * Setup: Automix's models downloaded once in the app (Settings → Automix), and a song of
 * 45 s or more, in any format the phone decodes, in the app's private files: the app can't
 * read a file adb puts in its external folder.
 *
 *     adb push song.mp3 /data/local/tmp/k
 *     adb shell "cat /data/local/tmp/k | run-as com.dev.bitchord sh -c 'cat > files/karaoke.m4a'"
 *     adb shell am instrument -w -e class com.music.bitchord.KaraokeInferenceBenchmark \
 *         com.dev.bitchord.test/androidx.test.runner.AndroidJUnitRunner
 *     adb exec-out run-as com.dev.bitchord cat files/karaoke-20.wav > karaoke-20.wav
 *
 * (`am instrument` rather than connectedAndroidTest, which uninstalls the app and its WAVs.)
 * Writes `karaoke-20.wav` (vocal at 20%) and `karaoke-0.wav` next to the song, and logs
 * under the tag `KaraokeBenchmark`.
 */
@RunWith(AndroidJUnit4::class)
class KaraokeInferenceBenchmark {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rate = VocalSpectrogram.sampleRate
    private val windowSamples = (VocalTracker.FIXED_FRAMES - 1) * VocalSpectrogram.hop
    private val stepSamples = (20 * rate).toInt() / VocalSpectrogram.hop * VocalSpectrogram.hop

    @Test
    fun measuresTheMaskAndRendersTheCut() {
        AutomixModels.install(context)
        val modelPath = runCatching { AutomixModels.path(AutomixModels.VOCALS) }.getOrNull()
        assertNotNull("Download Automix's models in the app first", modelPath)
        val song = File(context.filesDir, "karaoke.m4a")
        assertTrue("Push a song to ${song.absolutePath}", song.exists())

        val started = System.currentTimeMillis()
        val (pcm, _) = FileSource(song).use { AudioDecoder.decodeRegionStereo(it, 0.0, 45.0) }!!
        val left = MelSpectrogram.resample(pcm.left, pcm.sampleRate, rate)!!
        val right = MelSpectrogram.resample(pcm.right, pcm.sampleRate, rate)!!
        log("decoded ${left.size / rate}s at ${pcm.sampleRate} Hz in ${System.currentTimeMillis() - started}ms")

        // Threads: the same window at each count Automix's performance modes offer.
        var best = 1
        var bestMs = Long.MAX_VALUE
        for (threads in listOf(1, 2, 4)) {
            val tracker = VocalTracker({ modelPath!! }, { threads })
            tracker.mask(left.copyOf(windowSamples), right.copyOf(windowSamples), rate) // warm-up: session open
            val t0 = System.currentTimeMillis()
            val mask = tracker.mask(left.copyOf(windowSamples), right.copyOf(windowSamples), rate)
            val ms = System.currentTimeMillis() - t0
            tracker.release()
            assertNotNull("inference failed at $threads threads", mask)
            log("threads=$threads: ${ms}ms per ${windowSamples / rate}s window, " +
                "${"%.2f".format(windowSamples / rate / (ms / 1000.0))}x real time, " +
                "mask ${mask!!.values.size / 1024}KB (float32 would be ${mask.values.size * 4 / 1024}KB)")
            if (ms < bestMs) { bestMs = ms; best = threads }
        }

        val tracker = VocalTracker({ modelPath!! }, { best })
        val masks = (0 until left.size step stepSamples).map { start ->
            val end = minOf(start + windowSamples, left.size)
            tracker.mask(left.copyOfRange(start, end), right.copyOfRange(start, end), rate)!!
        }
        tracker.release()

        for (level in listOf(0.2f, 0f)) {
            val t0 = System.currentTimeMillis()
            val out = render(left, right, masks, level)
            log("cut at $level: ${System.currentTimeMillis() - t0}ms of DSP for ${left.size / rate}s")
            writeWav(File(song.parentFile, "karaoke-${(level * 100).toInt()}.wav"), out)
        }
        val realTime = windowSamples / rate / (bestMs / 1000.0)
        log("GATE: ${"%.2f".format(realTime)}x real time at $best threads (needs >= 1.5)")
        assertTrue("Perf gate: ${"%.2f".format(realTime)}x real time, needs 1.5x", realTime >= 1.5)
    }

    /**
     * Each frame reads the window it sits in, switching to the next one a second into the
     * overlap, so the reflection-padded edges of a window are never used. Seams fall at
     * ~21 s and ~41 s; listen there.
     */
    private fun render(left: FloatArray, right: FloatArray, masks: List<VocalTracker.VocalMask>, level: Float): FloatArray {
        val cut = VocalCut().apply { this.level = level; reset() }
        val frames = left.size + VocalCut.LATENCY
        val input = FloatArray(frames * 2)
        for (i in left.indices) { input[i * 2] = left[i]; input[i * 2 + 1] = right[i] }
        val output = FloatArray(frames * 2)
        val overlapToUse = (rate).toLong()
        cut.process(input, frames, output) { centre, channel, into ->
            if (centre < 0) return@process false
            var k = (centre / stepSamples).toInt().coerceAtMost(masks.size - 1)
            if (k > 0 && centre - k.toLong() * stepSamples < overlapToUse) k--
            val mask = masks[k]
            val frame = ((centre - k.toLong() * stepSamples) / VocalSpectrogram.hop).toInt()
            if (frame >= mask.frames) return@process false
            for (bin in into.indices) into[bin] = mask.at(channel, bin, frame)
            true
        }
        return output.copyOfRange(VocalCut.LATENCY * 2, output.size)
    }

    private fun writeWav(file: File, interleaved: FloatArray) {
        val data = ByteBuffer.allocate(interleaved.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in interleaved) data.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(rate.toInt())
            putInt(rate.toInt() * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(data.capacity())
        }
        file.outputStream().use { it.write(header.array()); it.write(data.array()) }
        log("wrote ${file.absolutePath}")
    }

    private fun log(message: String) = Log.i("KaraokeBenchmark", message)

    private class FileSource(file: File) : MediaDataSource() {
        private val raf = RandomAccessFile(file, "r")
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= raf.length()) return -1
            raf.seek(position)
            return raf.read(buffer, offset, size)
        }
        override fun getSize(): Long = raf.length()
        override fun close() = raf.close()
    }
}
