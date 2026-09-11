package com.lagradost.cloudstream3.companion.tv

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.companion.protocol.PlayRequest
import com.lagradost.cloudstream3.companion.protocol.PlaybackStateKind
import com.lagradost.cloudstream3.companion.protocol.ResolvedLink
import com.lagradost.cloudstream3.companion.protocol.ResolvedSubtitle
import com.lagradost.cloudstream3.companion.protocol.toExtractorLink
import com.lagradost.cloudstream3.companion.protocol.toSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink

data class CompanionPlaybackMetadata(
    val title: String,
    val episodeLabel: String?,
    val posterUrl: String?,
    val mediaId: Int?,
    val durationMs: Long?,
)

interface CompanionGeneratorPlayer {
    fun start(startPositionMs: Long?)
    fun release()
}

interface CompanionGeneratorPlaybackListener {
    fun onPlaybackState(state: PlaybackStateKind, positionMs: Long, durationMs: Long)
    fun onLinkFailure(linkIndex: Int, failure: PlaybackStartFailure)
    fun onPlaybackEnded()
    fun onLocalPlaybackChanged()
}

interface CompanionGeneratorPlayerEventSource {
    fun setPlaybackListener(listener: CompanionGeneratorPlaybackListener)
}

interface CompanionGeneratorFactory {
    fun create(
        link: ExtractorLink,
        subtitles: List<SubtitleFile>,
        metadata: CompanionPlaybackMetadata,
    ): CompanionGeneratorPlayer
}

interface CompanionNowPlayingReporter {
    fun register(lineageId: String, metadata: CompanionPlaybackMetadata)
    fun unregister(lineageId: String)
}

sealed class PlaybackStartResult {
    data object Started : PlaybackStartResult()

    data class Failed(
        val failure: PlaybackStartFailure,
    ) : PlaybackStartResult()
}

data class PlaybackStartFailure(
    val stage: StartupFailureStage,
    val httpStatus: Int? = null,
)

enum class StartupFailureStage {
    MANIFEST,
    SEGMENT,
    AUTH,
    UNKNOWN,
}

interface TvPlaybackLauncher {
    /** Starts one candidate and owns replacement/cleanup of any previous candidate. */
    suspend fun startCandidate(
        request: PlayRequest,
        candidate: ResolvedLink,
        startPositionMs: Long?,
        deadlineMs: Long,
    ): PlaybackStartResult

    fun stop()
}

/**
 * Adapter between the companion protocol and the app's GeneratorPlayer layer.
 * The Android player construction stays behind [CompanionGeneratorFactory].
 */
class CompanionPlaybackLauncher(
    private val factory: CompanionGeneratorFactory,
    private val reporter: CompanionNowPlayingReporter,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val playbackListener: CompanionGeneratorPlaybackListener? = null,
) : TvPlaybackLauncher {
    private var activePlayer: CompanionGeneratorPlayer? = null
    private var activeLineageId: String? = null
    private var activeGeneration = 0L
    private var generation = 0L

    override suspend fun startCandidate(
        request: PlayRequest,
        candidate: ResolvedLink,
        startPositionMs: Long?,
        deadlineMs: Long,
    ): PlaybackStartResult {
        if (nowMs() > deadlineMs) {
            return PlaybackStartResult.Failed(
                PlaybackStartFailure(stage = StartupFailureStage.UNKNOWN),
            )
        }

        val playerGeneration = ++generation
        releaseActive(invalidateGeneration = false)
        activeLineageId = request.lineageId
        activeGeneration = playerGeneration

        val player = try {
            factory.create(
                link = candidate.toExtractorLink(),
                subtitles = request.subtitles.map(ResolvedSubtitle::toSubtitleFile),
                metadata = CompanionPlaybackMetadata(
                    title = request.title,
                    episodeLabel = request.episodeLabel,
                    posterUrl = request.posterUrl,
                    mediaId = request.mediaId,
                    durationMs = request.durationMs,
                ),
            )
        } catch (_: Throwable) {
            clearActiveIdentity(playerGeneration)
            return PlaybackStartResult.Failed(
                PlaybackStartFailure(stage = StartupFailureStage.UNKNOWN),
            )
        }

        return try {
            if (playbackListener != null && player is CompanionGeneratorPlayerEventSource) {
                player.setPlaybackListener(
                    GenerationGuardedPlaybackListener(
                        delegate = playbackListener,
                        isCurrent = {
                            generation == playerGeneration &&
                                activeGeneration == playerGeneration &&
                                activeLineageId == request.lineageId
                        },
                    ),
                )
            }
            player.start(startPositionMs)
            if (generation != playerGeneration || activeGeneration != playerGeneration) {
                player.release()
                return PlaybackStartResult.Failed(
                    PlaybackStartFailure(stage = StartupFailureStage.UNKNOWN),
                )
            }
            if (nowMs() > deadlineMs) {
                clearActiveIdentity(playerGeneration)
                player.release()
                return PlaybackStartResult.Failed(
                    PlaybackStartFailure(stage = StartupFailureStage.UNKNOWN),
                )
            }
            activePlayer = player
            reporter.register(
                lineageId = request.lineageId,
                metadata = CompanionPlaybackMetadata(
                    title = request.title,
                    episodeLabel = request.episodeLabel,
                    posterUrl = request.posterUrl,
                    mediaId = request.mediaId,
                    durationMs = request.durationMs,
                ),
            )
            PlaybackStartResult.Started
        } catch (_: Throwable) {
            player.release()
            clearActiveIdentity(playerGeneration)
            PlaybackStartResult.Failed(
                PlaybackStartFailure(stage = StartupFailureStage.UNKNOWN),
            )
        }
    }

    override fun stop() {
        releaseActive(invalidateGeneration = true)
    }

    private fun clearActiveIdentity(playerGeneration: Long) {
        if (activeGeneration != playerGeneration) return
        activeGeneration = 0L
        activeLineageId = null
    }

    private fun releaseActive(invalidateGeneration: Boolean) {
        if (invalidateGeneration) generation += 1
        val lineageId = activeLineageId
        activeLineageId = null
        activeGeneration = 0L
        if (lineageId != null) reporter.unregister(lineageId)
        activePlayer?.release()
        activePlayer = null
    }
}

private class GenerationGuardedPlaybackListener(
    private val delegate: CompanionGeneratorPlaybackListener,
    private val isCurrent: () -> Boolean,
) : CompanionGeneratorPlaybackListener {
    override fun onPlaybackState(
        state: PlaybackStateKind,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (isCurrent()) delegate.onPlaybackState(state, positionMs, durationMs)
    }

    override fun onLinkFailure(linkIndex: Int, failure: PlaybackStartFailure) {
        if (isCurrent()) delegate.onLinkFailure(linkIndex, failure)
    }

    override fun onPlaybackEnded() {
        if (isCurrent()) delegate.onPlaybackEnded()
    }

    override fun onLocalPlaybackChanged() {
        if (isCurrent()) delegate.onLocalPlaybackChanged()
    }
}
