package com.lagradost.cloudstream3.remote

import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage.MinimalVideoLink
import java.net.URI
import java.net.URLDecoder

/**
 * Privacy-safe share-to-TV classification (plan F2, revised): the phone accepts ACTION_SEND
 * text/plain and ACTION_VIEW http(s) only while a paired TV session is online, always shows an
 * explicit confirmation, and sends PLAY only for **direct** TV-compatible absolute HTTP(S)
 * video/DASH/M3U8 links. There is deliberately no clipboard polling and no generic-extractor
 * fallback: a link that is not a direct media URL is never sent, and nothing is ever
 * auto-sent. Pure Kotlin so the whole policy stays JVM-unit-testable (see CompanionUiTest).
 */
data class SharedUrl(
    val url: String,
    /** Wire mime type for the link, one of video/mp4, application/dash+xml, application/x-mpegURL. */
    val mimeType: String,
)

/** Cap on the accepted shared URL length (keeps PLAY frames small; far below the 1 MiB cap). */
const val MAX_SHARE_URL_LENGTH = 8192

/** Direct container/segment extensions the TV can play without any extraction step. */
private val DIRECT_VIDEO_EXTENSIONS = setOf(
    "mp4", "m4v", "webm", "mkv", "mov", "avi", "3gp", "ts",
)

private val SHARE_URL_TOKEN = Regex("""https?://[^\s<>"']+""")

/** True when [raw] is a bare absolute http(s) URL with a host (regardless of media type). */
fun looksLikeHttpUrl(raw: String?): Boolean {
    val url = raw?.trim().orEmpty()
    if (url.isEmpty() || url.length > MAX_SHARE_URL_LENGTH) return false
    val parsed = runCatching { URI(url) }.getOrNull() ?: return false
    return parsed.scheme?.lowercase() in setOf("http", "https") && !parsed.host.isNullOrBlank()
}

/**
 * Extracts the URL candidate from a shared string: the whole share when it is already an
 * absolute http(s) URL, otherwise the first http(s) URL token inside surrounding text
 * ("Watch this: https://cdn.example/x.mp4" -> the mp4 link). Null when there is no URL.
 */
fun sharedUrlCandidate(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed.length > MAX_SHARE_URL_LENGTH) return null
    if (looksLikeHttpUrl(trimmed)) return trimmed
    return SHARE_URL_TOKEN.find(trimmed)?.value
        ?.takeIf { it.length <= MAX_SHARE_URL_LENGTH }
}

/**
 * Classifies a shared string as a direct TV-compatible link. Accepts only absolute http(s)
 * URLs whose path ends in a video extension (.mp4/.webm/...), .m3u8 or .mpd - i.e. links the
 * TV player can load directly. Everything else (provider pages, relative links, magnet, plain
 * text, ...) is null: the phone must never send PLAY for it (no generic resolution, F2).
 */
fun classifySharedUrl(raw: String?): SharedUrl? {
    val url = raw?.trim().orEmpty()
    if (url.isEmpty() || url.length > MAX_SHARE_URL_LENGTH) return null
    val parsed = runCatching { URI(url) }.getOrNull() ?: return null
    if (parsed.scheme?.lowercase() !in setOf("http", "https")) return null
    if (parsed.host.isNullOrBlank()) return null
    val mimeType = directVideoMimeType(parsed.path) ?: return null
    return SharedUrl(url, mimeType)
}

private fun directVideoMimeType(path: String?): String? {
    val lower = path?.lowercase() ?: return null
    return when {
        lower.endsWith(".m3u8") -> "application/x-mpegURL"
        lower.endsWith(".mpd") -> "application/dash+xml"
        DIRECT_VIDEO_EXTENSIONS.any { lower.endsWith(".$it") } -> "video/mp4"
        else -> null
    }
}

/** Friendly share title: the URL-decoded last path segment ("movie-1080p.mp4"), null at root. */
fun shareTitle(url: String): String? {
    val path = runCatching { URI(url).path }.getOrNull() ?: return null
    val segment = path.trimEnd('/').substringAfterLast('/')
        .takeIf { it.isNotBlank() } ?: return null
    return runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
}

/**
 * Builds the single-link [PlayPayload] for a confirmed share. The TV re-filters with
 * [com.lagradost.cloudstream3.ui.player.PlaybackCoordinator.tvCompatibleLinks] (defense in
 * depth): only this one direct, absolute, media-typed link is ever sent.
 */
fun buildSharePlayPayload(shared: SharedUrl): PlayPayload = PlayPayload(
    links = listOf(
        MinimalVideoLink(
            uri = null,
            url = shared.url,
            mimeType = shared.mimeType,
            name = shareTitle(shared.url) ?: shared.url,
            headers = emptyMap(),
            quality = null,
        )
    ),
    title = shareTitle(shared.url) ?: shared.url,
)
