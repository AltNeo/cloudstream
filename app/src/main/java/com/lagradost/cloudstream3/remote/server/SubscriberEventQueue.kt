package com.lagradost.cloudstream3.remote.server

import com.lagradost.cloudstream3.remote.RemoteEvent
import kotlinx.coroutines.channels.Channel

/**
 * Bounded FIFO of [RemoteEvent]s bound for one subscriber socket. Exactly one writer
 * coroutine drains it, so frames leave the socket in the order they were enqueued
 * (FIFO per subscriber). [enqueue] is non-blocking, so the player and main threads
 * never wait on a slow peer.
 *
 * A full queue rejects the incoming frame and the hub retires the slow subscriber. This bounds
 * memory without letting a newer snapshot evict an older non-replaceable event.
 */
internal class SubscriberEventQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val channel = Channel<RemoteEvent>(capacity)

    /** Non-blocking; false means closed or full and the subscriber must be retired. */
    fun enqueue(event: RemoteEvent): Boolean = channel.trySend(event).isSuccess

    /** Suspends until an event is available; null once the queue is closed and drained. */
    suspend fun receive(): RemoteEvent? = channel.receiveCatching().getOrNull()

    /** Stops the queue: the writer drains what is left, then exits. */
    fun close() {
        channel.close()
    }

    companion object {
        /** Queue depth per subscriber before overflow handling kicks in. */
        const val DEFAULT_CAPACITY = 256
    }
}
