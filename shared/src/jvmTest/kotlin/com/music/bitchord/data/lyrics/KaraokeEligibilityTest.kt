package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KaraokeEligibilityTest {
    private val timed = LyricLine(1_000, "sing along", words = listOf(
        LyricWord(1_000, 1_400, "sing"),
        LyricWord(1_400, 1_900, "along"),
    ))

    @Test
    fun `word-timed lyrics can be sung`() {
        assertTrue(listOf(LyricLine(0, ""), timed).canSing)
    }

    @Test
    fun `line-timed, plain and empty lyrics cannot`() {
        assertFalse(listOf(LyricLine(1_000, "sing along", sungUntilMs = 1_900)).canSing)
        assertFalse(emptyList<LyricLine>().canSing)
    }

    @Test
    fun `a translation keeps the original's word timing`() {
        val translated = LyricLine(1_000, "chante avec", timingSource = timed)
        assertTrue(listOf(translated).canSing)
    }
}
