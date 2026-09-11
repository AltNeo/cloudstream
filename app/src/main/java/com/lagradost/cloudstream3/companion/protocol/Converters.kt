package com.lagradost.cloudstream3.companion.protocol

import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.PlayListItem
import kotlinx.coroutines.runBlocking

fun ExtractorLink.toResolvedLink(
    issuedAtMs: Long,
    expiresAtMs: Long? = null,
): ResolvedLink {
    val linkType = type.toLinkType()
    return ResolvedLink(
        url = url,
        type = linkType,
        quality = quality,
        sourceName = source.ifBlank { name },
        referer = referer.ifBlank { null },
        headers = headers,
        audioTracks = audioTracks.map(AudioFile::toResolvedAudioTrack),
        playlist = (this as? ExtractorLinkPlayList)?.playlist?.map {
            PlaylistPart(url = it.url, durationUs = it.durationUs)
        },
        issuedAtMs = issuedAtMs,
        expiresAtMs = expiresAtMs,
    )
}

fun ResolvedLink.toExtractorLink(): ExtractorLink {
    val resolvedPlaylist = playlist
    val resolvedReferer = referer.orEmpty()
    val resolvedType = type.toExtractorLinkType()
    val resolvedAudioTracks = audioTracks.map(ResolvedAudioTrack::toAudioFile)
    return if (resolvedPlaylist != null) {
        ExtractorLinkPlayList(
            source = sourceName,
            name = sourceName,
            playlist = resolvedPlaylist.map { PlayListItem(it.url, it.durationUs) },
            referer = resolvedReferer,
            quality = quality,
            headers = headers,
            type = resolvedType,
            audioTracks = resolvedAudioTracks,
        )
    } else {
        ExtractorLink(
            source = sourceName,
            name = sourceName,
            url = url,
            referer = resolvedReferer,
            quality = quality,
            headers = headers,
            type = resolvedType,
            audioTracks = resolvedAudioTracks,
        )
    }
}

fun AudioFile.toResolvedAudioTrack(): ResolvedAudioTrack = ResolvedAudioTrack(
    url = url,
    headers = headers.orEmpty(),
)

fun ResolvedAudioTrack.toAudioFile(): AudioFile = runBlocking {
    newAudioFile(url) {
        headers = this@toAudioFile.headers
    }
}

fun SubtitleFile.toResolvedSubtitle(mimeType: String? = null): ResolvedSubtitle =
    ResolvedSubtitle(
        url = url,
        lang = lang,
        mimeType = mimeType,
        headers = headers.orEmpty(),
    )

fun ResolvedSubtitle.toSubtitleFile(): SubtitleFile = runBlocking {
    newSubtitleFile(lang = lang, url = url) {
        headers = this@toSubtitleFile.headers
    }
}

private fun ExtractorLinkType.toLinkType(): LinkType = when (this) {
    ExtractorLinkType.VIDEO -> LinkType.VIDEO
    ExtractorLinkType.M3U8 -> LinkType.M3U8
    ExtractorLinkType.DASH -> LinkType.DASH
    ExtractorLinkType.TORRENT,
    ExtractorLinkType.MAGNET,
    -> error("unsupported companion link type: $this")
}

private fun LinkType.toExtractorLinkType(): ExtractorLinkType = when (this) {
    LinkType.VIDEO -> ExtractorLinkType.VIDEO
    LinkType.M3U8 -> ExtractorLinkType.M3U8
    LinkType.DASH -> ExtractorLinkType.DASH
}
