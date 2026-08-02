package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.webkit.CookieManager
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.remote.CompanionSessionManager
import com.lagradost.cloudstream3.remote.PairingManager
import com.lagradost.cloudstream3.remote.PlayPayload
import com.lagradost.cloudstream3.remote.RemoteMessageType
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URI

enum class PrimaryPlaybackTarget {
    LOCAL_TV,
    REMOTE_TV,
    PAIR_TV,
}

/** Owns the app's primary Play target; the LAN companion is only a transport detail. */
object PlaybackCoordinator {
    val remoteSourceTypes = setOf(
        ExtractorLinkType.VIDEO,
        ExtractorLinkType.DASH,
        ExtractorLinkType.M3U8,
    )

    fun primaryTarget(context: Context): PrimaryPlaybackTarget = when {
        PairingManager.isTelevision(context) -> PrimaryPlaybackTarget.LOCAL_TV
        PairingManager.getActiveTv() != null -> PrimaryPlaybackTarget.REMOTE_TV
        else -> PrimaryPlaybackTarget.PAIR_TV
    }

    suspend fun playOnTv(
        context: Context,
        video: ResultEpisode,
        result: LinkLoadingResult,
    ) {
        val links = result.links
            .filter { it.type in remoteSourceTypes && isTvCompatibleUrl(it.url) }
            .filterNot { it is DrmExtractorLink || it is ExtractorLinkPlayList }
            .map { link ->
                CloudStreamPackage.MinimalVideoLink.fromExtractor(link).apply {
                    val cookies = runCatching {
                        CookieManager.getInstance().getCookie(link.url)
                    }.getOrNull()
                    if (!cookies.isNullOrBlank() && headers["Cookie"] == null) {
                        headers = headers + ("Cookie" to cookies)
                    }
                }
            }
        check(links.isNotEmpty()) { context.getString(R.string.companion_no_tv_links) }

        val resume = getViewPos(video.id)
        val response = CompanionSessionManager.send(
            RemoteMessageType.PLAY,
            PlayPayload(
                links = links,
                subtitles = result.subs.map {
                    CloudStreamPackage.MinimalSubtitleLink.fromSubtitle(it)
                },
                title = video.name,
                poster = video.poster,
                mediaId = video.id,
                positionMs = resume?.position,
                durationMs = resume?.duration,
            ),
        )
        if (!response.accepted) {
            if (response.error == "unauthenticated") {
                CommonActivity.showToast(R.string.companion_repair_required)
            }
            throw IllegalStateException(response.error ?: "TV rejected playback")
        }
    }

    /** Provider link lists can contain relative pages or non-media schemes; never send those to TV. */
    internal fun isTvCompatibleUrl(url: String): Boolean {
        val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        return parsed.scheme?.lowercase() in setOf("http", "https") && !parsed.host.isNullOrBlank()
    }

    fun tvCompatibleLinks(
        links: List<CloudStreamPackage.MinimalVideoLink>,
    ): List<CloudStreamPackage.MinimalVideoLink> = links.filter { link ->
        isTvCompatibleUrl(link.url ?: "") && link.mimeType in setOf(
            ExtractorLinkType.VIDEO.getMimeType(),
            ExtractorLinkType.DASH.getMimeType(),
            ExtractorLinkType.M3U8.getMimeType(),
        )
    }
}
