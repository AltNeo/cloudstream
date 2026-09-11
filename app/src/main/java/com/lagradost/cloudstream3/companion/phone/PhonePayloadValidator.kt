package com.lagradost.cloudstream3.companion.phone

import com.lagradost.cloudstream3.companion.protocol.PlayRequest

/** Validates every nested URL/header before a PLAY payload leaves the phone. */
object PhonePayloadValidator {
    fun isValid(request: PlayRequest): Boolean = runCatching {
        require(request.links.isNotEmpty())
        request.links.forEach { link ->
            require(isSafeCompanionUrl(link.url))
            require(sanitizeCompanionHeaders(link.headers).size == link.headers.size)
            link.referer?.let { require(isSafeCompanionUrl(it)) }
            link.audioTracks.forEach { audio ->
                require(isSafeCompanionUrl(audio.url))
                require(sanitizeCompanionHeaders(audio.headers).size == audio.headers.size)
            }
            link.playlist?.forEach { part ->
                require(isSafeCompanionUrl(part.url))
                require(part.durationUs >= 0L)
            }
        }
        request.subtitles.forEach { subtitle ->
            require(isSafeCompanionUrl(subtitle.url))
            require(sanitizeCompanionHeaders(subtitle.headers).size == subtitle.headers.size)
        }
    }.isSuccess
}
