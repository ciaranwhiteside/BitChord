package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDownloadTest {

    private fun song(id: String) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist $id",
        thumbnailUrl = "https://example.test/$id.jpg",
        durationText = "3:21",
        albumName = "Album",
        sourceQuality = "LOSSLESS",
    )

    @Test
    fun codecPreservesOrderAndReconstructionFieldsAcrossRestart() {
        val jobs = listOf(
            PendingDownload.from(song("a"), "album", enqueuedAt = 1L),
            PendingDownload.from(song("b"), null, enqueuedAt = 2L),
        )
        val decoded = DownloadJobCodec.decode(DownloadJobCodec.encode(jobs))
        assertEquals(listOf("a", "b"), decoded.map { it.videoId })
        assertEquals("Title a", decoded[0].toSong().title)
        assertEquals("Artist b", decoded[1].toSong().artist)
        assertEquals("LOSSLESS", decoded[0].toSong().sourceQuality)
        assertEquals("album", decoded[0].from)
        assertEquals(2L, decoded[1].enqueuedAt)
    }

    @Test
    fun malformedJournalIsEmptyRatherThanPartiallyTrusted() {
        assertTrue(DownloadJobCodec.decode("not json").isEmpty())
        assertTrue(DownloadJobCodec.decode(null).isEmpty())
    }

    @Test
    fun stoppedDrainKeepsOnlyJournaledWorkInItsOriginalOrder() {
        val jobs = linkedMapOf(
            "a" to PendingDownload.from(song("a"), "album"),
            "b" to PendingDownload.from(song("b"), null),
            "c" to PendingDownload.from(song("c"), null),
        )
        val recovered = recoverableDownloads(
            ids = linkedSetOf("c", "ephemeral", "a"),
            jobs = jobs,
        )
        assertEquals(listOf("a", "c"), recovered.map { it.videoId })
    }

    @Test
    fun stoppedDrainDropsEveryIdWithoutAJournalEntry() {
        val jobs = linkedMapOf("a" to PendingDownload.from(song("a"), null))
        val recovered = recoverableDownloads(linkedSetOf("ephemeral"), jobs)
        assertTrue(recovered.isEmpty())
    }

    @Test
    fun explicitFlagSurvivesRestartSoTheRightCutIsMatched() {
        val explicit = song("e").copy(isExplicit = true)
        val decoded = DownloadJobCodec.decode(DownloadJobCodec.encode(listOf(PendingDownload.from(explicit, null))))
        assertEquals(true, decoded.single().toSong().isExplicit)
    }

    @Test
    fun journalWrittenBeforeTheExplicitFieldStillDecodes() {
        val old = """[{"videoId":"x","title":"T","artist":"A"}]"""
        val decoded = DownloadJobCodec.decode(old)
        assertEquals("x", decoded.single().videoId)
        assertEquals(null, decoded.single().toSong().isExplicit)
    }

    @Test
    fun recoveryStandsDownOnlyWhileThisProcessIsDraining() {
        val now = 1_000_000L
        assertTrue(shouldSkipRecovery(serviceAlive = true, requestedAtMs = 0L, nowMs = now))
        // Asked moments ago: onCreate simply has not run yet.
        assertTrue(shouldSkipRecovery(serviceAlive = false, requestedAtMs = now - 2_000L, nowMs = now))
        // A failed or long-past ask never suppresses recovery.
        assertFalse(shouldSkipRecovery(serviceAlive = false, requestedAtMs = 0L, nowMs = now))
        assertFalse(
            shouldSkipRecovery(serviceAlive = false, requestedAtMs = now - SERVICE_START_GRACE_MS, nowMs = now),
        )
    }
}
