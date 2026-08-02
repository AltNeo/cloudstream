package com.lagradost.cloudstream3.remote

import android.content.Context
import android.webkit.CookieManager
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt

/**
 * "Play on TV" (plan §6.2). Resolves links on the phone, enriches headers with WebView
 * cookies (logins + Cloudflare clearance), keeps the provider `source` so the TV player can
 * find `getVideoInterceptor()`, and sends a signed v2 PlayPayload. The TV runs no extractors.
 */
class RemotePlayAction : VideoClickAction() {
    override val name = txt(R.string.play_on_tv)
    override val isPlayer = true
    override val isCasting = true
    override val sourceTypes = setOf(
        ExtractorLinkType.VIDEO,
        ExtractorLinkType.DASH,
        ExtractorLinkType.M3U8,
    )

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean {
        return PairingManager.getActiveTv() != null
    }

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
    ) {
        context ?: error("No activity")
        val links = result.links
            .filterNot { it is DrmExtractorLink || it is ExtractorLinkPlayList }
            .map { link ->
                CloudStreamPackage.MinimalVideoLink.fromExtractor(link).apply {
                    // Cookie/header enrichment: every credential required for playback travels
                    // as explicit per-request headers; the TV needs zero login state (plan §6.2).
                    val cookies = runCatching {
                        CookieManager.getInstance().getCookie(link.url)
                    }.getOrNull()
                    if (!cookies.isNullOrBlank() && headers["Cookie"] == null) {
                        headers = headers + ("Cookie" to cookies)
                    }
                }
            }
        check(links.isNotEmpty()) { "No TV-compatible links were found" }

        val resume = getViewPos(video.id)
        val payload = PlayPayload(
            links = links,
            subtitles = result.subs.map {
                CloudStreamPackage.MinimalSubtitleLink.fromSubtitle(it)
            },
            title = video.name,
            poster = video.poster,
            mediaId = video.id,
            positionMs = resume?.position,
            durationMs = resume?.duration,
        )
        val response = runCatching {
            CompanionSessionManager.send(RemoteMessageType.PLAY, payload)
        }.getOrElse {
            throw IllegalStateException(it.message ?: "TV unreachable")
        }
        if (!response.accepted) {
            val message = if (response.error == "unauthenticated") {
                CommonActivity.showToast(context.getString(R.string.companion_repair_required))
                "TV requires re-pairing (unauthenticated)"
            } else {
                response.error ?: "TV rejected playback"
            }
            // Fail loudly: a play click must never appear to succeed without playing
            // (review finding 11).
            throw IllegalStateException(message)
        }
    }
}
