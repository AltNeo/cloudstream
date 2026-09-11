package com.lagradost.cloudstream3.companion.tv

import com.lagradost.cloudstream3.companion.protocol.PlayRequest
import com.lagradost.cloudstream3.companion.protocol.ResolvedLink
import java.net.URI

internal fun PlayRequest.isValidForCompanion(): Boolean {
    if (lineageId.isBlank() || attempt < 0 || links.isEmpty()) return false
    if (!links.all { it.isValidForCompanion() }) return false
    return subtitles.all { subtitle ->
        isHttpUrl(subtitle.url) && subtitle.headers.keys.all(::isAllowedHeader)
    }
}

private fun ResolvedLink.isValidForCompanion(): Boolean {
    if (playlist != null && playlist.isEmpty()) return false
    if (playlist == null && !isHttpUrl(url)) return false
    if (playlist != null && url.isNotBlank() && !isHttpUrl(url)) return false
    if (playlist?.any { !isHttpUrl(it.url) } == true) return false
    if (!headers.keys.all(::isAllowedHeader)) return false
    if (!audioTracks.all { track ->
            isHttpUrl(track.url) && track.headers.keys.all(::isAllowedHeader)
        }) return false
    if (!playlist.orEmpty().all { part -> part.durationUs >= 0L }) return false
    return true
}

private fun isHttpUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    (uri.scheme.equals("http", ignoreCase = true) ||
        uri.scheme.equals("https", ignoreCase = true)) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        uri.fragment == null
}.getOrDefault(false)

private fun isAllowedHeader(name: String): Boolean {
    return name.equals("User-Agent", ignoreCase = true) ||
        name.equals("Cookie", ignoreCase = true) ||
        name.equals("Authorization", ignoreCase = true) ||
        name.equals("Origin", ignoreCase = true) ||
        name.equals("Accept", ignoreCase = true) ||
        name.equals("Accept-Language", ignoreCase = true) ||
        name.startsWith("x-", ignoreCase = true)
}
