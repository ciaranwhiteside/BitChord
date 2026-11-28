package com.music.bitchord.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLogRedactionTest {

    @Test
    fun `signed media URL parameters are redacted`() {
        val text = redactSensitive("https://rr1.googlevideo.com/a?itag=251&sig=SECRET&pot=TOKEN&expire=1")
        assertFalse(text.contains("SECRET"))
        assertFalse(text.contains("TOKEN"))
        assertTrue(text.contains("sig=<redacted>"))
    }

    @Test
    fun `authorization and addon proof headers are redacted`() {
        assertEquals(
            "Authorization: Bearer <redacted>",
            redactSensitive("Authorization: Bearer abc.def"),
        )
        assertEquals(
            "X-BitChord-Token: <redacted>",
            redactSensitive("X-BitChord-Token: durable-token"),
        )
    }

    @Test
    fun `ordinary diagnostics are untouched`() {
        assertEquals("resolve: 3 candidates", redactSensitive("resolve: 3 candidates"))
        // A parameter that merely ends in a sensitive word is not one.
        assertEquals("a?monkey=1&mode=2", redactSensitive("a?monkey=1&mode=2"))
    }

    @Test
    fun `second signature, api keys, basic auth and cookies are redacted`() {
        val url = redactSensitive("https://rr1.googlevideo.com/a?lsig=LSIGSECRET&api_key=K1&access_token=T1")
        listOf("LSIGSECRET", "K1", "T1").forEach { assertFalse("$it leaked: $url", url.contains(it)) }
        assertEquals("Authorization: Basic <redacted>", redactSensitive("Authorization: Basic dXNlcjpwYXNz"))
        assertEquals("Cookie: <redacted>", redactSensitive("Cookie: SAPISID=abc; SID=def"))
    }

    @Test
    fun `stack traces recorded with an error are redacted too`() {
        val error = IllegalStateException("HTTP 403 for https://rr1.googlevideo.com/a?sig=TRACESECRET")
        val line = TrackLog.withTrace("stream failed", error)
        assertTrue(line.contains("IllegalStateException"))
        assertTrue(line.contains("sig=<redacted>"))
        assertFalse(line.contains("TRACESECRET"))
    }
}
