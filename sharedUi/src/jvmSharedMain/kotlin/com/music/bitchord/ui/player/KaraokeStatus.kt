package com.music.bitchord.ui.player

/** What Karaoke is doing, as the lyrics view's button shows it. */
enum class KaraokeStatus {
    Off,

    /** On, the vocal model or this part of the song's mask still on its way; the audio plays as it is. */
    Preparing,

    /** On, and the vocal is turned down to the slider. */
    Singing,

    /** On, but this output carries the track untouched (bit-perfect, or a mono stream), so there is nothing to cut. */
    NotOnThisOutput,

    /** The vocal model couldn't run, or this track couldn't be read for it. */
    Failed,
}
