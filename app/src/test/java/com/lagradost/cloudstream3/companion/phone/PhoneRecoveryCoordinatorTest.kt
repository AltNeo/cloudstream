package com.lagradost.cloudstream3.companion.phone

import com.lagradost.cloudstream3.companion.protocol.LinkFailed
import com.lagradost.cloudstream3.companion.protocol.LinkFailureStage
import com.lagradost.cloudstream3.companion.protocol.PlayRequest
import com.lagradost.cloudstream3.companion.protocol.ResolvedLink
import com.lagradost.cloudstream3.companion.protocol.LinkType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRecoveryCoordinatorTest {
    @Test
    fun `stale and non-active failures do not re-resolve`() = runBlocking {
        val resolver = RecordingResolver()
        val coordinator = coordinator(resolver)
        val lineage = coordinator.start()
        waitFor { resolver.attempts.isNotEmpty() }
        coordinator.onLinkFailed(LinkFailed("other", 0, 0, stage = LinkFailureStage.UNKNOWN), null)
        coordinator.onLinkFailed(LinkFailed(lineage, 1, 0, stage = LinkFailureStage.UNKNOWN), null)
        assertEquals(listOf(0), resolver.attempts.map { it.attempt })
        coordinator.stop()
    }

    @Test
    fun `matching failures increment attempt and retry at most twice`() = runBlocking {
        val resolver = RecordingResolver()
        val coordinator = coordinator(resolver)
        val lineage = coordinator.start()
        waitFor { resolver.attempts.size == 1 }
        coordinator.onLinkFailed(LinkFailed(lineage, 0, 0, stage = LinkFailureStage.UNKNOWN), 10)
        waitFor { resolver.attempts.size == 2 }
        coordinator.onLinkFailed(LinkFailed(lineage, 1, 0, stage = LinkFailureStage.UNKNOWN), 20)
        waitFor { resolver.attempts.size == 3 }
        coordinator.onLinkFailed(LinkFailed(lineage, 2, 0, stage = LinkFailureStage.UNKNOWN), 30)
        waitFor { coordinator.activeLineageId == null }
        assertEquals(listOf(0, 1, 2), resolver.attempts.map { it.attempt })
        assertEquals(RecoveryTerminalReason.RETRY_LIMIT, resolver.terminal.single().reason)
    }

    @Test
    fun `starting a new lineage cancels old work`() = runBlocking {
        val resolver = RecordingResolver()
        val coordinator = coordinator(resolver)
        val first = coordinator.start()
        waitFor { resolver.attempts.isNotEmpty() }
        val second = coordinator.start()
        assertTrue(first != second)
        assertEquals(second, coordinator.activeLineageId)
        coordinator.stop()
    }

    @Test
    fun `concurrent replacement cannot send a cancelled generation`() = runBlocking {
        val resolver = BlockingResolver()
        val sent = mutableListOf<PlayRequest>()
        val coordinator = PhoneRecoveryCoordinator(
            scope = this,
            resolver = resolver,
            sender = PlayRequestSender {
                sent += it
                true
            },
            clock = CompanionClock { 1_000L },
        )
        val first = coordinator.start()
        assertEquals(first, resolver.started.receive().lineageId)
        val second = coordinator.start()
        assertEquals(second, resolver.started.receive().lineageId)
        assertEquals(second, coordinator.activeLineageId)
        kotlinx.coroutines.delay(25)
        assertTrue(sent.isEmpty())
        coordinator.stop()
    }

    @Test
    fun `disconnect cancels active lineage and reports exactly once`() = runBlocking {
        val resolver = RecordingResolver()
        val coordinator = coordinator(resolver)
        coordinator.start()
        waitFor { resolver.attempts.isNotEmpty() }
        coordinator.onDisconnected()
        assertNull(coordinator.activeLineageId)
        assertEquals(RecoveryTerminalReason.DISCONNECTED, resolver.terminal.single().reason)
    }

    private fun CoroutineScope.coordinator(resolver: RecordingResolver) =
        PhoneRecoveryCoordinator(
            scope = this,
            resolver = resolver,
            sender = PlayRequestSender { true },
            clock = CompanionClock { 1_000L },
            onTerminalFailure = { resolver.terminal += it },
        )

    private suspend fun waitFor(predicate: () -> Boolean) {
        withTimeout(2_000L) {
            while (!predicate()) kotlinx.coroutines.delay(1)
        }
    }

    private class RecordingResolver : PlayRequestResolver {
        val attempts = mutableListOf<ResolveAttempt>()
        val terminal = mutableListOf<RecoveryTerminalFailure>()

        override suspend fun resolve(attempt: ResolveAttempt): PlayRequest {
            attempts += attempt
            return PlayRequest(
                lineageId = attempt.lineageId,
                attempt = attempt.attempt,
                links = listOf(
                    ResolvedLink(
                        url = "https://cdn.example/video.mp4",
                        type = LinkType.VIDEO,
                        quality = 1,
                        sourceName = "test",
                        issuedAtMs = 1,
                    )
                ),
                subtitles = emptyList(),
                title = "test",
            )
        }
    }

    private class BlockingResolver : PlayRequestResolver {
        val started = Channel<ResolveAttempt>(Channel.UNLIMITED)

        override suspend fun resolve(attempt: ResolveAttempt): PlayRequest {
            started.send(attempt)
            awaitCancellation()
        }
    }
}
