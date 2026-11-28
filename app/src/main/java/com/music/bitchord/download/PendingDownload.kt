/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The minimum durable description of a queued download. The full [Song] is
 * intentionally not serialized; the resolver only needs these fields to
 * rebuild the row and its destination metadata after a process restart.
 */
@Serializable
internal data class PendingDownload(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val albumName: String? = null,
    val radioName: String? = null,
    val isVideo: Boolean = false,
    val sourceQuality: String? = null,
    val sourceHiRes: Boolean = false,
    val from: String? = null,
    val enqueuedAt: Long = 0L,
    /**
     * TrackMatcher.targetOf reads it to pick the explicit or the clean cut when
     * a download is matched on another source; without it a download resumed
     * after process death could come back as the radio edit. Defaulted, so a
     * journal written before this field still decodes.
     */
    val isExplicit: Boolean? = null,
) {
    fun toSong(): Song = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        albumName = albumName,
        radioName = radioName,
        isVideo = isVideo,
        sourceQuality = sourceQuality,
        sourceHiRes = sourceHiRes,
        isExplicit = isExplicit,
    )

    companion object {
        fun from(song: Song, from: String?, enqueuedAt: Long = System.currentTimeMillis()) = PendingDownload(
            videoId = song.videoId,
            title = song.title,
            artist = song.artist,
            thumbnailUrl = song.thumbnailUrl,
            durationText = song.durationText,
            albumName = song.albumName,
            radioName = song.radioName,
            isVideo = song.isVideo,
            sourceQuality = song.sourceQuality,
            sourceHiRes = song.sourceHiRes,
            from = from,
            enqueuedAt = enqueuedAt,
            isExplicit = song.isExplicit,
        )
    }
}

/** Versioned JSON codec for the durable pending-download journal. */
internal object DownloadJobCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val list = ListSerializer(PendingDownload.serializer())

    fun encode(jobs: Collection<PendingDownload>): String =
        json.encodeToString(list, jobs.toList())

    fun decode(raw: String?): List<PendingDownload> =
        runCatching { json.decodeFromString(list, raw ?: return emptyList()) }
            .getOrDefault(emptyList())
}

/**
 * The jobs still worth resuming when a drain stops, in journal order.
 *
 * A track with no journal entry (one fetched by [Downloads.downloadNow]) has
 * nowhere to be restored from and is not included; everything with one keeps
 * its original place whether it was waiting or already running.
 */
internal fun recoverableDownloads(
    ids: Collection<String>,
    jobs: Map<String, PendingDownload>,
): List<PendingDownload> = jobs.values.filter { it.videoId in ids }
