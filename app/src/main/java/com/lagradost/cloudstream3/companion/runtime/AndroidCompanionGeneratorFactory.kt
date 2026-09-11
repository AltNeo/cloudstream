package com.lagradost.cloudstream3.companion.runtime

import android.net.Uri
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.companion.protocol.toExtractorLink
import com.lagradost.cloudstream3.companion.tv.CompanionGeneratorFactory
import com.lagradost.cloudstream3.companion.tv.CompanionGeneratorPlayer
import com.lagradost.cloudstream3.companion.tv.CompanionPlaybackMetadata
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.VideoGenerator
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.SubtitleFile

/** Android composition for companion playback using the normal GeneratorPlayer fragment. */
class AndroidCompanionGeneratorFactory(
    private val activityProvider: () -> FragmentActivity? = {
        CommonActivity.activity as? FragmentActivity
    },
) : CompanionGeneratorFactory {
    override fun create(
        link: ExtractorLink,
        subtitles: List<SubtitleFile>,
        metadata: CompanionPlaybackMetadata,
    ): CompanionGeneratorPlayer {
        return AndroidCompanionGeneratorPlayer(activityProvider, link, subtitles, metadata)
    }
}

private class AndroidCompanionGeneratorPlayer(
    private val activityProvider: () -> FragmentActivity?,
    private val link: ExtractorLink,
    private val subtitles: List<SubtitleFile>,
    private val metadata: CompanionPlaybackMetadata,
) : CompanionGeneratorPlayer {
    private var started = false

    override fun start(startPositionMs: Long?) {
        check(!started) { "companion player already started" }
        val activity = activityProvider() ?: error("no foreground activity for companion playback")
        val generator = CompanionLinkGenerator(link, subtitles, metadata)
        val args = GeneratorPlayer.newInstance(generator, 0).apply {
            if (startPositionMs != null) putLong("companionStartPositionMs", startPositionMs)
        }
        started = true
        activity.runOnUiThread {
            if (!started) return@runOnUiThread
            activity.navigate(R.id.global_to_navigation_player, args)
        }
    }

    override fun release() {
        if (!started) return
        started = false
        com.lagradost.cloudstream3.companion.ui.CompanionPlayerController.dispatch("STOP")
    }
}

private class CompanionLinkGenerator(
    private val link: ExtractorLink,
    private val subtitles: List<SubtitleFile>,
    metadata: CompanionPlaybackMetadata,
) : VideoGenerator<ExtractorUri>(
    videos = listOf(
        ExtractorUri(
            uri = Uri.parse(link.url),
            name = metadata.title,
            displayName = metadata.episodeLabel,
            id = metadata.mediaId,
            headerName = metadata.title,
        ),
    ),
) {
    override val hasCache: Boolean = false
    override val canSkipLoading: Boolean = false

    override fun getId(index: Int): Int? = videos.getOrNull(index)?.id

    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean,
    ): Boolean {
        if (offset != 0 || link.type !in sourceTypes) return false
        callback(link to null)
        subtitles.forEach { subtitleCallback(PlayerSubtitleHelper.getSubtitleData(it)) }
        return true
    }
}
