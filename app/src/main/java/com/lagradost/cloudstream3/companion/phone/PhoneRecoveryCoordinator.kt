package com.lagradost.cloudstream3.companion.phone

import com.lagradost.cloudstream3.companion.protocol.LinkFailed
import com.lagradost.cloudstream3.companion.protocol.PlayRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

data class ResolveAttempt(
    val lineageId: String,
    val attempt: Int,
    val startPositionMs: Long?,
)

fun interface PlayRequestResolver {
    suspend fun resolve(attempt: ResolveAttempt): PlayRequest?
}

fun interface PlayRequestSender {
    suspend fun send(request: PlayRequest): Boolean
}

enum class RecoveryTerminalReason {
    RESOLUTION_FAILED,
    DRM_ONLY,
    SEND_FAILED,
    RETRY_LIMIT,
    BUDGET_EXPIRED,
    DISCONNECTED,
}

data class RecoveryTerminalFailure(
    val lineageId: String,
    val reason: RecoveryTerminalReason,
)

/** Owns one phone recovery lineage and cancels every superseded resolution job. */
class PhoneRecoveryCoordinator(
    private val scope: CoroutineScope,
    private val resolver: PlayRequestResolver,
    private val sender: PlayRequestSender,
    private val clock: CompanionClock,
    private val onTerminalFailure: (RecoveryTerminalFailure) -> Unit = {},
) {
    private val stateLock = Any()
    private val sendLock = Mutex()
    @Volatile
    private var active: ActiveSession? = null
    private var nextGeneration = 0L

    val activeLineageId: String?
        get() = active?.lineageId

    fun start(startPositionMs: Long? = null): String {
        val lineageId = UUID.randomUUID().toString()
        synchronized(stateLock) {
            active?.job?.cancel()
            ActiveSession(lineageId, ++nextGeneration).also {
                active = it
                it.job = scope.launch { sendAttempt(it, 0, startPositionMs) }
            }
        }
        return lineageId
    }

    suspend fun onLinkFailed(event: LinkFailed, lastPositionMs: Long?) {
        val action = synchronized(stateLock) {
            val current = active ?: return
            if (event.lineageId != current.lineageId || event.attempt != current.lastSentAttempt ||
                current.failureInFlightAttempt == event.attempt
            ) {
                return
            }
            if (current.recoveryStartedAtMs == null) {
                current.recoveryStartedAtMs = clock.nowMs()
            }
            current.failureInFlightAttempt = event.attempt
            val shouldRetry = current.retryCount < MAX_AUTOMATIC_RETRIES
            if (shouldRetry) current.retryCount += 1
            FailureAction(current, shouldRetry)
        }
        val session = action.session
        val replacementJob = scope.launch {
            if (!action.shouldRetry) {
                terminal(session, RecoveryTerminalReason.RETRY_LIMIT)
                return@launch
            }
            val now = clock.nowMs()
            val started = session.recoveryStartedAtMs ?: now
            if (now - started >= RECOVERY_BUDGET_MS) {
                terminal(session, RecoveryTerminalReason.BUDGET_EXPIRED)
                return@launch
            }
            val attempt = synchronized(stateLock) {
                if (active !== session || session.lastSentAttempt == null) return@synchronized null
                session.lastSentAttempt!! + 1
            } ?: return@launch
            sendAttempt(session, attempt, lastPositionMs)
        }
        synchronized(stateLock) {
            if (active === session) {
                session.job?.cancel()
                session.job = replacementJob
            } else {
                replacementJob.cancel()
            }
        }
    }

    suspend fun stop() {
        val session = synchronized(stateLock) {
            val current = active ?: return
            active = null
            current
        }
        session.job?.cancel()
    }

    suspend fun onDisconnected() {
        val session = synchronized(stateLock) {
            val current = active ?: return
            active = null
            current
        }
        session.job?.cancel()
        onTerminalFailure(
            RecoveryTerminalFailure(session.lineageId, RecoveryTerminalReason.DISCONNECTED)
        )
    }

    private suspend fun sendAttempt(
        session: ActiveSession,
        attempt: Int,
        startPositionMs: Long?,
    ) {
        val request = try {
            val timeout = remainingBudget(session)
            withTimeout(timeout) {
                resolver.resolve(ResolveAttempt(session.lineageId, attempt, startPositionMs))
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            terminal(session, RecoveryTerminalReason.BUDGET_EXPIRED)
            return
        } catch (_: CancellationException) {
            return
        } catch (_: Throwable) {
            terminal(session, RecoveryTerminalReason.RESOLUTION_FAILED)
            return
        }
        if (request == null || !isCurrent(session)) {
            terminal(session, RecoveryTerminalReason.RESOLUTION_FAILED)
            return
        }
        if (request.lineageId != session.lineageId || request.attempt != attempt) {
            terminal(session, RecoveryTerminalReason.RESOLUTION_FAILED)
            return
        }
        val sent = try {
            val timeout = if (session.recoveryStartedAtMs == null) {
                INITIAL_ATTEMPT_TIMEOUT_MS
            } else {
                remainingBudget(session)
            }
            sendLock.withLock {
                if (!isCurrent(session)) return
                withTimeout(timeout) { sender.send(request) }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            terminal(session, RecoveryTerminalReason.BUDGET_EXPIRED)
            return
        } catch (_: CancellationException) {
            return
        } catch (_: Throwable) {
            false
        }
        if (!sent) {
            terminal(session, RecoveryTerminalReason.SEND_FAILED)
            return
        }
        synchronized(stateLock) {
            if (active === session) {
                session.lastSentAttempt = attempt
                session.failureInFlightAttempt = null
            }
        }
    }

    private suspend fun remainingBudget(session: ActiveSession): Long {
        val started = synchronized(stateLock) {
            session.recoveryStartedAtMs
        } ?: return INITIAL_ATTEMPT_TIMEOUT_MS
        return (RECOVERY_BUDGET_MS - (clock.nowMs() - started)).coerceAtLeast(1L)
    }

    private suspend fun terminal(session: ActiveSession, reason: RecoveryTerminalReason) {
        val shouldNotify = synchronized(stateLock) {
            if (active !== session) return
            active = null
            true
        }
        if (shouldNotify) {
            session.job?.cancel()
            onTerminalFailure(RecoveryTerminalFailure(session.lineageId, reason))
        }
    }

    private fun isCurrent(session: ActiveSession): Boolean = synchronized(stateLock) {
        active?.let { it === session && it.generation == session.generation } == true
    }

    private class ActiveSession(val lineageId: String, val generation: Long) {
        var job: Job? = null
        var lastSentAttempt: Int? = null
        var retryCount = 0
        var recoveryStartedAtMs: Long? = null
        var failureInFlightAttempt: Int? = null
    }

    private data class FailureAction(
        val session: ActiveSession,
        val shouldRetry: Boolean,
    )

    companion object {
        const val MAX_AUTOMATIC_RETRIES = 2
        const val RECOVERY_BUDGET_MS = 60_000L
        const val INITIAL_ATTEMPT_TIMEOUT_MS = 30_000L
    }
}
