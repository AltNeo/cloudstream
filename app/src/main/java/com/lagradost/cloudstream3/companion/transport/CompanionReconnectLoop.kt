package com.lagradost.cloudstream3.companion.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CompanionReconnectLoop(
    private val scope: CoroutineScope,
    private val connect: suspend () -> Unit,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val onFailure: (Throwable) -> Unit = {},
    private val initialDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 30_000L,
) {
    private val mutex = Mutex()
    private var generation = 0L
    private var job: Job? = null

    suspend fun start() = mutex.withLock {
        if (job?.isActive == true) return@withLock
        val currentGeneration = ++generation
        job = scope.launch {
            var backoffMs = initialDelayMs
            while (isActive && generation == currentGeneration) {
                try {
                    connect()
                    backoffMs = initialDelayMs
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    onFailure(error)
                    sleep(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(maxDelayMs)
                }
            }
        }
    }

    suspend fun stop() = mutex.withLock {
        ++generation
        job?.cancel()
        job = null
    }
}
