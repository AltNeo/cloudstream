package com.lagradost.cloudstream3.companion.phone

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.companion.protocol.PlayRequest
import com.lagradost.cloudstream3.companion.protocol.PlaylistPart
import com.lagradost.cloudstream3.companion.protocol.ResolvedLink
import com.lagradost.cloudstream3.companion.protocol.ResolvedAudioTrack
import com.lagradost.cloudstream3.companion.protocol.ResolvedSubtitle
import com.lagradost.cloudstream3.companion.protocol.toResolvedLink
import com.lagradost.cloudstream3.companion.protocol.toResolvedSubtitle
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Collections
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Clock injected so expiry and recovery budgets can be deterministic in JVM tests. */
fun interface CompanionClock {
    fun nowMs(): Long
}

interface CompanionHeaderProvider {
    fun webViewUserAgent(): String?

    /** Returns cookies in Cookie-header form for the supplied URL. */
    fun cookiesFor(url: String): String?
}

data class ProbeResponse(
    val statusCode: Int,
    val finalUrl: String,
    val contentType: String? = null,
    val body: ByteArray = ByteArray(0),
)

private const val MAX_HEADER_COUNT = 64
private const val MAX_HEADER_NAME_CHARS = 128
private const val MAX_HEADER_VALUE_BYTES = 8 * 1024
private const val MAX_TOTAL_HEADER_BYTES = 32 * 1024

private val SENSITIVE_REDIRECT_HEADERS = setOf(
    "authorization",
    "cookie",
    "origin",
    "referer",
    "user-agent",
)

internal fun isSafeCompanionUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme?.lowercase() in setOf("http", "https") &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        uri.fragment == null
}.getOrDefault(false)

internal fun sanitizeCompanionHeaders(
    headers: Map<String, String>,
    includeReferer: Boolean = false,
): Map<String, String> {
    require(headers.size <= MAX_HEADER_COUNT) { "too many companion headers" }
    val sanitized = linkedMapOf<String, String>()
    var totalBytes = 0
    headers.forEach { (name, value) ->
        require(name.length in 1..MAX_HEADER_NAME_CHARS) { "invalid companion header name" }
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_HEADER_VALUE_BYTES) {
            "companion header value is too large"
        }
        val allowed = name.equals("User-Agent", true) ||
            name.equals("Cookie", true) ||
            name.equals("Authorization", true) ||
            name.equals("Origin", true) ||
            name.equals("Accept", true) ||
            name.equals("Accept-Language", true) ||
            name.startsWith("x-", true) ||
            (includeReferer && name.equals("Referer", true))
        if (!allowed) return@forEach
        totalBytes += name.toByteArray(Charsets.UTF_8).size + value.toByteArray(Charsets.UTF_8).size
        require(totalBytes <= MAX_TOTAL_HEADER_BYTES) { "companion headers are too large" }
        val existing = sanitized.keys.firstOrNull { it.equals(name, true) }
        if (existing == null) sanitized[name] = value else sanitized[existing] = value
    }
    return sanitized
}

private fun originOf(value: String): String? = runCatching {
    val uri = URI(value)
    val scheme = uri.scheme?.lowercase() ?: return@runCatching null
    val host = uri.host?.lowercase() ?: return@runCatching null
    val port = if (uri.port == -1) {
        if (scheme == "https") 443 else 80
    } else uri.port
    "$scheme://$host:$port"
}.getOrNull()

internal fun headersForCompanionRedirect(
    headers: Map<String, String>,
    fromUrl: String,
    toUrl: String,
): Map<String, String> {
    val fromOrigin = originOf(fromUrl)
    val toOrigin = originOf(toUrl)
    if (fromOrigin != null && fromOrigin == toOrigin) return headers
    return headers.filterKeys {
        !SENSITIVE_REDIRECT_HEADERS.contains(it.lowercase()) && !it.startsWith("x-", true)
    }
}

internal fun stripSensitiveCompanionHeaders(headers: Map<String, String>): Map<String, String> =
    headers.filterKeys {
        !SENSITIVE_REDIRECT_HEADERS.contains(it.lowercase()) && !it.startsWith("x-", true)
    }

/** A phone OkHttp adapter should enforce its own connection/read timeout as well. */
interface CandidateProbe {
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        range: String? = null,
    ): ProbeResponse
}

/** OkHttp-backed probe used by the Android phone integration. */
class OkHttpCandidateProbe(
    private val client: OkHttpClient,
) : CandidateProbe {
    private val redirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        range: String?,
    ): ProbeResponse {
        require(isSafeCompanionUrl(url)) { "invalid probe URL" }
        var currentUrl = url
        var currentHeaders = sanitizeCompanionHeaders(headers, includeReferer = true)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val request = Request.Builder().url(currentUrl).get().apply {
                currentHeaders.forEach { (name, value) -> header(name, value) }
                if (range != null) header("Range", range)
            }.build()
            val response = redirectClient.newCall(request).execute()
            val location = response.header("Location")
            if (response.code in 300..399 && !location.isNullOrBlank()) {
                val nextUrl = URI(currentUrl).resolve(location).toString()
                response.close()
                require(isSafeCompanionUrl(nextUrl)) { "invalid redirect URL" }
                currentHeaders = headersForCompanionRedirect(currentHeaders, currentUrl, nextUrl)
                currentUrl = nextUrl
                if (redirectCount == MAX_REDIRECTS) throw IOException("too many redirects")
                return@repeat
            }
            response.use {
                return ProbeResponse(
                    statusCode = it.code,
                    finalUrl = it.request.url.toString(),
                    contentType = it.header("Content-Type"),
                    body = readProbeBody(it.body),
                )
            }
        }
        throw IOException("too many redirects")
    }

    private fun readProbeBody(body: okhttp3.ResponseBody?): ByteArray {
        if (body == null) return ByteArray(0)
        body.byteStream().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(PROBE_BODY_LIMIT)
            var remaining = PROBE_BODY_LIMIT
            while (remaining > 0) {
                val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
            return output.toByteArray()
        }
    }

    companion object {
        private const val PROBE_BODY_LIMIT = 256 * 1024
        private const val MAX_REDIRECTS = 5
    }
}

data class LinkResolutionInput(
    val links: List<ExtractorLink>,
    val subtitles: List<SubtitleFile>,
    val title: String,
    val episodeLabel: String? = null,
    val posterUrl: String? = null,
    val mediaId: Int? = null,
    val startPositionMs: Long? = null,
    val durationMs: Long? = null,
    val lineageId: String,
    val attempt: Int = 0,
)

sealed class LinkResolutionOutcome {
    data class Success(val request: PlayRequest) : LinkResolutionOutcome()

    data class NoCandidates(val droppedDrmLinks: Int) : LinkResolutionOutcome()

    data object BudgetExpired : LinkResolutionOutcome()
}

/**
 * Resolves and validates links on the phone before handing them to the TV.
 *
 * This class deliberately has no Android dependency. WebView and CookieManager values are
 * supplied through [CompanionHeaderProvider] by the Android integration layer.
 */
class LinkResolutionPipeline(
    private val probe: CandidateProbe,
    private val clock: CompanionClock,
    private val headerProvider: CompanionHeaderProvider = object : CompanionHeaderProvider {
        override fun webViewUserAgent(): String? = null
        override fun cookiesFor(url: String): String? = null
    },
) {
    suspend fun resolve(input: LinkResolutionInput): LinkResolutionOutcome = coroutineScope {
        val startedAt = clock.nowMs()
        val candidates = input.links.mapIndexedNotNull { index, link ->
            if (link is DrmExtractorLink || !link.isHttpUrl()) return@mapIndexedNotNull null
            runCatching { Candidate(index, link, mergedHeaders(link)) }.getOrNull()
        }
        val drmCount = input.links.count { it is DrmExtractorLink }
        if (candidates.isEmpty()) return@coroutineScope LinkResolutionOutcome.NoCandidates(drmCount)

        val limited = candidates.take(MAX_CANDIDATES)
        val semaphore = Semaphore(MAX_PARALLEL_PROBES)
        val remaining = ASSEMBLY_BUDGET_MS - (clock.nowMs() - startedAt)
        if (remaining <= 0L) return@coroutineScope LinkResolutionOutcome.BudgetExpired

        val completed = Collections.synchronizedList(mutableListOf<ValidatedCandidate>())
        val jobs = limited.map { candidate ->
            async {
                semaphore.withPermit {
                    validateSafely(candidate)?.also { completed += it }
                }
            }
        }
        val finished = withTimeoutOrNull(remaining) {
            jobs.awaitAll()
            true
        } ?: false
        if (!finished) jobs.forEach { it.cancel() }
        val results = completed.toList()

        if (results.isEmpty()) {
            return@coroutineScope if (
                !finished || clock.nowMs() - startedAt >= ASSEMBLY_BUDGET_MS
            ) {
                LinkResolutionOutcome.BudgetExpired
            } else {
                LinkResolutionOutcome.NoCandidates(drmCount)
            }
        }

        val ordered = results.sortedWith(
            compareByDescending<ValidatedCandidate> { it.link.quality }
                .thenBy { it.index }
        )
        val subtitles = input.subtitles.mapNotNull { subtitle ->
            if (!isSafeCompanionUrl(subtitle.url)) return@mapNotNull null
            val headers = runCatching { sanitizeCompanionHeaders(subtitle.headers.orEmpty()) }
                .getOrNull() ?: return@mapNotNull null
            subtitle.toResolvedSubtitle().copy(headers = headers)
        }
        LinkResolutionOutcome.Success(
            PlayRequest(
                lineageId = input.lineageId,
                attempt = input.attempt,
                links = ordered.map { it.link },
                subtitles = subtitles,
                title = input.title,
                episodeLabel = input.episodeLabel,
                posterUrl = input.posterUrl,
                mediaId = input.mediaId,
                startPositionMs = input.startPositionMs,
                durationMs = input.durationMs,
            )
        )
    }

    private suspend fun validate(candidate: Candidate): ValidatedCandidate {
        val link = candidate.link
        val normalized = when {
            link is ExtractorLinkPlayList -> probePlaylist(link, candidate.headers)
            link.type == ExtractorLinkType.M3U8 -> probeHls(link.url, candidate.headers)
            link.type == ExtractorLinkType.DASH -> probeDash(link.url, candidate.headers)
            else -> probeDirect(link.url, candidate.headers)
        }
        val finalHeaders = sanitizeCompanionHeaders(
            headersForCompanionRedirect(
                if (normalized.sensitiveHeadersAllowed) candidate.headers
                else stripSensitiveCompanionHeaders(candidate.headers),
                link.urlForProbe(),
                normalized.url,
            )
        )
        val finalReferer = link.referer.takeIf { referer ->
            referer.isNotBlank() && isSafeCompanionUrl(referer) &&
                originOf(referer) == originOf(normalized.url)
        }
        val resolvedAudioTracks = link.audioTracks.map { audio ->
            require(isSafeCompanionUrl(audio.url)) { "invalid audio track URL" }
            ResolvedAudioTrack(audio.url, sanitizeCompanionHeaders(audio.headers.orEmpty()))
        }
        val resolved = link.toResolvedLink(clock.nowMs()).copy(
            url = normalized.url,
            playlist = normalized.playlist,
            headers = finalHeaders,
            referer = finalReferer,
            audioTracks = resolvedAudioTracks,
            expiresAtMs = estimateExpiry(normalized.url, clock.nowMs()),
        )
        return ValidatedCandidate(candidate.index, resolved)
    }

    private suspend fun validateSafely(candidate: Candidate): ValidatedCandidate? = try {
        validate(candidate)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private suspend fun probeDirect(
        url: String,
        headers: Map<String, String>,
    ): NormalizedProbe {
        val response = request(url, headers, "bytes=0-1023")
        require(response.statusCode in 200..299)
        val contentType = response.contentType.orEmpty().lowercase()
        require(contentType.startsWith("video/") || response.body.isNotEmpty())
        return NormalizedProbe(response.finalUrl)
    }

    private suspend fun probeHls(
        url: String,
        headers: Map<String, String>,
    ): NormalizedProbe {
        var sensitiveHeadersAllowed = originOf(url) != null
        val manifest = request(url, headers)
        require(manifest.statusCode in 200..299)
        sensitiveHeadersAllowed = sensitiveHeadersAllowed &&
            originOf(url) == originOf(manifest.finalUrl)
        val text = manifest.body.toString(Charsets.UTF_8)
        require(text.lineSequence().any { it.trim() == "#EXTM3U" })

        val lines = text.lines().map(String::trim)
        val variant = lines.dropWhile { !it.startsWith("#EXT-X-STREAM-INF") }
            .drop(1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        val mediaUrl = variant?.let { resolveUrl(manifest.finalUrl, it) } ?: manifest.finalUrl
        sensitiveHeadersAllowed = sensitiveHeadersAllowed &&
            originOf(manifest.finalUrl) == originOf(mediaUrl)
        val media = if (variant != null) {
            request(mediaUrl, headers, fromUrl = manifest.finalUrl)
        } else {
            manifest
        }
        require(media.statusCode in 200..299)
        val mediaText = media.body.toString(Charsets.UTF_8)
        require(mediaText.lineSequence().any { it.trim() == "#EXTM3U" })
        val segment = mediaText.lines().map(String::trim).firstOrNull {
            it.isNotEmpty() && !it.startsWith("#")
        }
        if (segment != null) {
            val segmentUrl = resolveUrl(media.finalUrl, segment)
            sensitiveHeadersAllowed = sensitiveHeadersAllowed &&
                originOf(media.finalUrl) == originOf(segmentUrl)
            val segmentResponse = request(segmentUrl, headers, fromUrl = media.finalUrl)
            require(segmentResponse.statusCode in 200..299)
        }
        return NormalizedProbe(manifest.finalUrl, sensitiveHeadersAllowed = sensitiveHeadersAllowed)
    }

    private suspend fun probeDash(
        url: String,
        headers: Map<String, String>,
    ): NormalizedProbe {
        var sensitiveHeadersAllowed = originOf(url) != null
        val manifest = request(url, headers)
        require(manifest.statusCode in 200..299)
        sensitiveHeadersAllowed = sensitiveHeadersAllowed &&
            originOf(url) == originOf(manifest.finalUrl)
        val text = manifest.body.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        require(Regex("<MPD(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(text))
        val base = Regex("<BaseURL[^>]*>([^<]+)</BaseURL>", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.trim()
        if (!base.isNullOrBlank()) {
            val segmentUrl = resolveUrl(manifest.finalUrl, base)
            sensitiveHeadersAllowed = sensitiveHeadersAllowed &&
                originOf(manifest.finalUrl) == originOf(segmentUrl)
            val segment = request(
                segmentUrl,
                headers,
                "bytes=0-1023",
                fromUrl = manifest.finalUrl,
            )
            require(segment.statusCode in 200..299)
        }
        return NormalizedProbe(manifest.finalUrl, sensitiveHeadersAllowed = sensitiveHeadersAllowed)
    }

    private suspend fun probePlaylist(
        link: ExtractorLinkPlayList,
        headers: Map<String, String>,
    ): NormalizedProbe {
        require(link.playlist.isNotEmpty())
        val parts = link.playlist
        var sensitiveHeadersAllowed = originOf(parts.first().url) != null
        val first = request(parts.first().url, headers)
        require(first.statusCode in 200..299)
        sensitiveHeadersAllowed = sensitiveHeadersAllowed &&
            originOf(parts.first().url) == originOf(first.finalUrl)
        val normalized = parts.mapIndexed { index, part ->
            when (index) {
                0 -> part.copy(url = first.finalUrl)
                in 1..MAX_EXTRA_PLAYLIST_PARTS -> {
                    sensitiveHeadersAllowed = sensitiveHeadersAllowed &&
                        originOf(parts.first().url) == originOf(part.url)
                    val response = request(
                        part.url,
                        headers,
                        "bytes=0-1023",
                        fromUrl = parts.first().url,
                    )
                    require(response.statusCode in 200..299)
                    sensitiveHeadersAllowed = sensitiveHeadersAllowed &&
                        originOf(part.url) == originOf(response.finalUrl)
                    part.copy(url = response.finalUrl)
                }
                else -> part
            }
        }
        return NormalizedProbe(
            first.finalUrl,
            normalized.map { PlaylistPart(it.url, it.durationUs) },
            sensitiveHeadersAllowed,
        )
    }

    private suspend fun request(
        url: String,
        headers: Map<String, String>,
        range: String? = null,
        fromUrl: String = url,
    ): ProbeResponse = withTimeout(PER_REQUEST_TIMEOUT_MS) {
        require(isSafeCompanionUrl(url)) { "invalid probe URL" }
        probe.get(url, headersForCompanionRedirect(headers, fromUrl, url), range)
    }

    private fun mergedHeaders(link: ExtractorLink): Map<String, String> {
        val output = linkedMapOf<String, String>()
        fun add(name: String, value: String?) {
            if (value.isNullOrBlank() || !isAllowedHeader(name)) return
            val existing = output.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            if (existing == null) {
                output[name] = value
            } else if (name.equals("Cookie", ignoreCase = true)) {
                output[existing] = listOf(output[existing], value)
                    .filterNotNull()
                    .joinToString("; ")
            } else {
                output[existing] = value
            }
        }
        link.headers.forEach { (name, value) -> add(name, value) }
        add("Referer", link.referer)
        add("User-Agent", headerProvider.webViewUserAgent())
        add("Cookie", headerProvider.cookiesFor(link.url))
        link.extractorData?.let { data ->
            runCatching { URI(data).host }.getOrNull()?.let { host ->
                add("Cookie", headerProvider.cookiesFor("https://$host/"))
            }
        }
        return sanitizeCompanionHeaders(output, includeReferer = true)
    }

    private fun isAllowedHeader(name: String): Boolean =
        name.equals("User-Agent", true) ||
            name.equals("Cookie", true) ||
            name.equals("Authorization", true) ||
            name.equals("Origin", true) ||
            name.equals("Accept", true) ||
            name.equals("Accept-Language", true) ||
            name.startsWith("x-", true) ||
            name.equals("Referer", true)

    private fun ExtractorLink.isHttpUrl(): Boolean {
        return isSafeCompanionUrl(urlForProbe())
    }

    private fun ExtractorLink.urlForProbe(): String =
        (this as? ExtractorLinkPlayList)?.playlist?.firstOrNull()?.url ?: url

    private fun resolveUrl(base: String, child: String): String =
        runCatching { URI(base).resolve(child).toString() }.getOrDefault(child)

    private fun estimateExpiry(url: String, nowMs: Long): Long? {
        val query = runCatching { URI(url).rawQuery.orEmpty() }.getOrDefault("")
        return query.split('&').asSequence().mapNotNull { pair ->
            val split = pair.split('=', limit = 2)
            if (split.size != 2) return@mapNotNull null
            val key = split[0].lowercase()
            val value = split[1].toLongOrNull() ?: return@mapNotNull null
            if (key != "expires" && key != "exp" && !key.contains("token")) return@mapNotNull null
            val millis = if (value < 100_000_000_000L) value * 1_000L else value
            if (millis > nowMs - EXPIRY_PAST_TOLERANCE_MS) millis else null
        }.firstOrNull()
    }

    private data class Candidate(
        val index: Int,
        val link: ExtractorLink,
        val headers: Map<String, String>,
    )

    private data class ValidatedCandidate(val index: Int, val link: ResolvedLink)

    private data class NormalizedProbe(
        val url: String,
        val playlist: List<PlaylistPart>? = null,
        val sensitiveHeadersAllowed: Boolean = true,
    )

    companion object {
        const val MAX_CANDIDATES = 4
        const val MAX_PARALLEL_PROBES = 3
        const val PER_REQUEST_TIMEOUT_MS = 10_000L
        const val ASSEMBLY_BUDGET_MS = 30_000L
        const val MAX_EXTRA_PLAYLIST_PARTS = 2
        const val EXPIRY_PAST_TOLERANCE_MS = 5_000L
    }
}
