package com.lagradost.cloudstream3.companion.tv

import com.lagradost.cloudstream3.companion.protocol.Event
import com.lagradost.cloudstream3.companion.protocol.EventKind
import com.lagradost.cloudstream3.companion.protocol.ErrorCode
import com.lagradost.cloudstream3.companion.protocol.LinkFailed
import com.lagradost.cloudstream3.companion.protocol.LinkFailureStage
import com.lagradost.cloudstream3.companion.protocol.NavigationDirection
import com.lagradost.cloudstream3.companion.protocol.NavRequested
import com.lagradost.cloudstream3.companion.protocol.PlayRequest
import com.lagradost.cloudstream3.companion.protocol.PlaybackState
import com.lagradost.cloudstream3.companion.protocol.PlaybackStateKind
import com.lagradost.cloudstream3.companion.protocol.PlayerAction
import com.lagradost.cloudstream3.companion.protocol.PlayerCommand
import com.lagradost.cloudstream3.companion.protocol.ResultPayload
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.LinkedHashMap

data class ActiveRemoteSession(
    val deviceId: String,
    val lineageId: String,
    val attempt: Int,
    val title: String,
    val episodeLabel: String?,
    val posterUrl: String?,
    val mediaId: Int?,
    val durationMs: Long?,
)

enum class TvStatusMessage {
    ASKING_PHONE_FOR_FRESH_LINK,
    PHONE_DISCONNECTED,
    CONNECT_PHONE_TO_CHANGE_EPISODES,
}

interface CompanionTvSessionSink {
    fun sendResult(requestId: String, result: ResultPayload)
    fun sendEvent(event: Event)
    fun showStatus(message: TvStatusMessage)
}

object NoOpCompanionTvSessionSink : CompanionTvSessionSink {
    override fun sendResult(requestId: String, result: ResultPayload) = Unit
    override fun sendEvent(event: Event) = Unit
    override fun showStatus(message: TvStatusMessage) = Unit
}

fun interface CompanionClock {
    fun nowMs(): Long
}

sealed class PlayAcceptance {
    data object Started : PlayAcceptance()
    data object WaitingForRecovery : PlayAcceptance()
    data class Rejected(val error: ErrorCode) : PlayAcceptance()
}

private data class PlayStartContext(
    val generation: Long,
    val request: PlayRequest,
    val startPositionMs: Long,
)

private sealed class PlayDecision {
    data class Accepted(val context: PlayStartContext) : PlayDecision()
    data class Rejected(val error: ErrorCode) : PlayDecision()
}

class CompanionTvSessionController(
    private val launcher: TvPlaybackLauncher,
    private val sink: CompanionTvSessionSink = NoOpCompanionTvSessionSink,
    private val clock: CompanionClock = CompanionClock(System::currentTimeMillis),
    private val startupDeadlineMs: Long = 20_000L,
) {
    private val mutex = Mutex()
    private val endedLineages = LinkedHashMap<String, Unit>()
    private val connectedDevices = mutableSetOf<String>()
    private var active: ActiveRemoteSession? = null
    private var waitingForRecovery = false
    private var lastPositionMs = 0L
    private var generation = 0L

    private companion object {
        const val MAX_ENDED_LINEAGES = 128
    }

    suspend fun onPhoneConnected(deviceId: String) = mutex.withLock {
        connectedDevices += deviceId
    }

    suspend fun handlePlay(
        deviceId: String,
        requestId: String,
        request: PlayRequest,
    ): PlayAcceptance {
        val decision = mutex.withLock {
            if (!request.isValidForCompanion()) {
                return@withLock PlayDecision.Rejected(ErrorCode.INVALID_PAYLOAD)
            }

            val current = active
            if (request.lineageId in endedLineages ||
                (current != null && request.lineageId == current.lineageId &&
                    request.attempt <= current.attempt)
            ) {
                return@withLock PlayDecision.Rejected(ErrorCode.STALE_REQUEST)
            }

            if (current != null && request.lineageId != current.lineageId) {
                rememberEndedLineage(current.lineageId)
            }

            active = ActiveRemoteSession(
                deviceId = deviceId,
                lineageId = request.lineageId,
                attempt = request.attempt,
                title = request.title,
                episodeLabel = request.episodeLabel,
                posterUrl = request.posterUrl,
                mediaId = request.mediaId,
                durationMs = request.durationMs,
            )
            waitingForRecovery = false
            lastPositionMs = request.startPositionMs ?: 0L
            generation += 1
            PlayDecision.Accepted(
                PlayStartContext(
                    generation = generation,
                    request = request,
                    startPositionMs = lastPositionMs,
                ),
            )
        }

        val context = when (decision) {
            is PlayDecision.Rejected -> {
                sink.sendResult(
                    requestId,
                    ResultPayload(ok = false, error = decision.error),
                )
                return PlayAcceptance.Rejected(decision.error)
            }
            is PlayDecision.Accepted -> decision.context
        }
        val request = context.request
        var lastFailure: PlaybackStartFailure? = null
        var lastIndex = -1
        request.links.forEachIndexed { index, candidate ->
            if (!isCurrent(context)) return@forEachIndexed
            if (candidate.expiresAtMs != null && candidate.expiresAtMs <= clock.nowMs()) {
                return@forEachIndexed
            }
            lastIndex = index
            val deadlineMs = clock.nowMs() + startupDeadlineMs
            val result = try {
                withTimeout(startupDeadlineMs) {
                    launcher.startCandidate(
                        request = request,
                        candidate = candidate,
                        startPositionMs = context.startPositionMs,
                        deadlineMs = deadlineMs,
                    )
                }
            } catch (_: TimeoutCancellationException) {
                PlaybackStartResult.Failed(
                    PlaybackStartFailure(stage = StartupFailureStage.UNKNOWN),
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Throwable) {
                PlaybackStartResult.Failed(
                    PlaybackStartFailure(stage = StartupFailureStage.UNKNOWN),
                )
            }

            if (
                result is PlaybackStartResult.Started &&
                    clock.nowMs() <= deadlineMs &&
                    isCurrent(context)
            ) {
                sink.sendResult(requestId, ResultPayload(ok = true))
                return PlayAcceptance.Started
            }
            lastFailure = when (result) {
                is PlaybackStartResult.Failed -> result.failure
                PlaybackStartResult.Started -> PlaybackStartFailure(StartupFailureStage.UNKNOWN)
            }
        }

        if (!isCurrent(context)) {
            sink.sendResult(
                requestId,
                ResultPayload(ok = false, error = ErrorCode.STALE_REQUEST),
            )
            return PlayAcceptance.Rejected(ErrorCode.STALE_REQUEST)
        }

        val canWait = mutex.withLock {
            if (!isCurrentLocked(context)) {
                false
            } else {
                waitingForRecovery = true
                true
            }
        }
        if (!canWait) {
            sink.sendResult(
                requestId,
                ResultPayload(ok = false, error = ErrorCode.STALE_REQUEST),
            )
            return PlayAcceptance.Rejected(ErrorCode.STALE_REQUEST)
        }
        val failure = lastFailure ?: PlaybackStartFailure(StartupFailureStage.UNKNOWN)
        emitLinkFailed(
            lineageId = request.lineageId,
            attempt = request.attempt,
            linkIndex = lastIndex.coerceAtLeast(0),
            failure = failure,
        )
        sink.sendResult(requestId, ResultPayload(ok = true))
        return PlayAcceptance.WaitingForRecovery
    }

    suspend fun handlePlayerCommand(
        deviceId: String,
        requestId: String,
        command: PlayerCommand,
    ): ResultPayload = mutex.withLock {
        val session = active
        if (session == null) {
            return@withLock sendCommandResult(
                requestId,
                ResultPayload(ok = false, error = ErrorCode.NO_ACTIVE_PLAYER),
            )
        }
        if (session.deviceId != deviceId) {
            return@withLock sendCommandResult(
                requestId,
                ResultPayload(ok = false, error = ErrorCode.NOT_AUTHORIZED),
            )
        }
        val direction = when (command.action) {
            PlayerAction.NEXT -> NavigationDirection.NEXT
            PlayerAction.PREV -> NavigationDirection.PREV
            else -> return@withLock sendCommandResult(
                requestId,
                ResultPayload(ok = false, error = ErrorCode.UNSUPPORTED),
            )
        }
        emitNavigationRequested(session, direction)
        sendCommandResult(requestId, ResultPayload(ok = true))
    }

    suspend fun onPlaybackEnded(): Boolean = mutex.withLock {
        val session = active ?: return@withLock false
        if (session.deviceId !in connectedDevices) {
            sink.showStatus(TvStatusMessage.CONNECT_PHONE_TO_CHANGE_EPISODES)
            return@withLock false
        }
        emitNavigationRequested(session, NavigationDirection.NEXT)
        true
    }

    suspend fun onPlaybackPosition(positionMs: Long) = mutex.withLock {
        lastPositionMs = positionMs.coerceAtLeast(0L)
    }

    suspend fun onPlaybackFailure(
        linkIndex: Int,
        failure: PlaybackStartFailure,
    ) = mutex.withLock {
        val session = active ?: return@withLock
        waitingForRecovery = true
        emitLinkFailed(
            lineageId = session.lineageId,
            attempt = session.attempt,
            linkIndex = linkIndex,
            failure = failure,
        )
        sink.showStatus(TvStatusMessage.ASKING_PHONE_FOR_FRESH_LINK)
    }

    suspend fun onPhoneDisconnected(deviceId: String) {
        val session = mutex.withLock {
            connectedDevices -= deviceId
            val current = active
            if (current?.deviceId == deviceId && waitingForRecovery) {
                rememberEndedLineage(current.lineageId)
                active = null
                waitingForRecovery = false
                generation += 1
                current
            } else {
                null
            }
        }
        if (session != null) {
            launcher.stop()
            sink.showStatus(TvStatusMessage.PHONE_DISCONNECTED)
            emitIdle(session)
        }
    }

    suspend fun onPhoneUnpaired(deviceId: String) {
        val session = mutex.withLock {
            connectedDevices -= deviceId
            val current = active
            if (current?.deviceId == deviceId) {
                rememberEndedLineage(current.lineageId)
                active = null
                waitingForRecovery = false
                generation += 1
                current
            } else {
                null
            }
        }
        if (session != null) {
            launcher.stop()
            emitIdle(session)
        }
    }

    suspend fun onLocalPlaybackChanged() {
        val session = mutex.withLock {
            val current = active ?: return@withLock null
            rememberEndedLineage(current.lineageId)
            active = null
            waitingForRecovery = false
            generation += 1
            current
        }
        if (session != null) {
            launcher.stop()
            emitIdle(session)
        }
    }

    suspend fun snapshot(): ActiveRemoteSession? = mutex.withLock { active }

    private suspend fun isCurrent(context: PlayStartContext): Boolean = mutex.withLock {
        isCurrentLocked(context)
    }

    private fun isCurrentLocked(context: PlayStartContext): Boolean =
        generation == context.generation &&
            active?.lineageId == context.request.lineageId &&
            active?.attempt == context.request.attempt

    private fun rememberEndedLineage(lineageId: String) {
        endedLineages[lineageId] = Unit
        while (endedLineages.size > MAX_ENDED_LINEAGES) {
            endedLineages.remove(endedLineages.entries.first().key)
        }
    }

    private fun sendCommandResult(requestId: String, result: ResultPayload): ResultPayload {
        sink.sendResult(requestId, result)
        return result
    }

    private fun emitLinkFailed(
        lineageId: String,
        attempt: Int,
        linkIndex: Int,
        failure: PlaybackStartFailure,
    ) {
        sink.sendEvent(
            Event(
                kind = EventKind.LINK_FAILED,
                linkFailed = LinkFailed(
                    lineageId = lineageId,
                    attempt = attempt,
                    linkIndex = linkIndex,
                    httpStatus = failure.httpStatus,
                    stage = failure.stage.toProtocolStage(),
                ),
            ),
        )
        sink.showStatus(TvStatusMessage.ASKING_PHONE_FOR_FRESH_LINK)
    }

    private fun emitNavigationRequested(
        session: ActiveRemoteSession,
        direction: NavigationDirection,
    ) {
        sink.sendEvent(
            Event(
                kind = EventKind.NAV_REQUESTED,
                navRequested = NavRequested(session.lineageId, direction),
            ),
        )
    }

    private fun emitIdle(session: ActiveRemoteSession) {
        sink.sendEvent(
            Event(
                kind = EventKind.PLAYBACK_STATE,
                playbackState = PlaybackState(
                    lineageId = session.lineageId,
                    title = session.title,
                    episodeLabel = session.episodeLabel,
                    posterUrl = session.posterUrl,
                    positionMs = lastPositionMs,
                    durationMs = session.durationMs ?: 0L,
                    state = PlaybackStateKind.IDLE,
                    mediaId = session.mediaId,
                ),
            ),
        )
    }
}

private fun StartupFailureStage.toProtocolStage(): LinkFailureStage = when (this) {
    StartupFailureStage.MANIFEST -> LinkFailureStage.MANIFEST
    StartupFailureStage.SEGMENT -> LinkFailureStage.SEGMENT
    StartupFailureStage.AUTH -> LinkFailureStage.AUTH
    StartupFailureStage.UNKNOWN -> LinkFailureStage.UNKNOWN
}
