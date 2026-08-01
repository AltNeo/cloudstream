package com.lagradost.cloudstream3.remote

import android.content.Context
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt

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
        return LanRemoteClient.selectedEndpoint(context) != null
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
            .map { CloudStreamPackage.MinimalVideoLink.fromExtractor(it).toJson() }
        check(links.isNotEmpty()) { "No TV-compatible links were found" }

        val resume = getViewPos(video.id)
        val response = LanRemoteClient.send(
            context,
            LanRemoteRequest(
                command = LanRemoteCommand.PLAY,
                play = LanRemotePlayPayload(
                    links = links,
                    subtitles = result.subs.map {
                        CloudStreamPackage.MinimalSubtitleLink.fromSubtitle(it).toJson()
                    },
                    title = video.name,
                    mediaId = video.id,
                    positionMs = resume?.position,
                    durationMs = resume?.duration,
                ),
            ),
        )
        check(response.accepted) { response.message ?: "TV rejected playback" }
    }
}
