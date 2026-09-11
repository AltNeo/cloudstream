package com.lagradost.cloudstream3.companion.tv

import com.lagradost.cloudstream3.companion.protocol.Event
import com.lagradost.cloudstream3.companion.protocol.PlaybackStateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionNowPlayingHubTest {
    @Test
    fun `playing progress is throttled while transitions emit immediately`() {
        var now = 0L
        val events = mutableListOf<Event>()
        val hub = CompanionNowPlayingHub(
            clock = CompanionClock { now },
            eventSink = events::add,
        )
        hub.register(
            lineageId = "lineage",
            metadata = CompanionPlaybackMetadata(
                title = "Title",
                episodeLabel = "S1E1",
                posterUrl = null,
                mediaId = 1,
                durationMs = 1000L,
            ),
        )
        assertEquals(1, events.size)

        now = 1_000L
        assertFalse(hub.onPlaybackState(PlaybackStateKind.PLAYING, 100L, 1000L))
        assertEquals(1, events.size)
        now = 3_000L
        assertTrue(hub.onPlaybackState(PlaybackStateKind.PLAYING, 300L, 1000L))
        assertEquals(2, events.size)

        assertTrue(hub.onPlaybackState(PlaybackStateKind.PAUSED, 400L, 1000L))
        assertEquals(3, events.size)
        assertEquals(PlaybackStateKind.PAUSED, events.last().playbackState?.state)
    }

    @Test
    fun `unregister emits idle and clears snapshot`() {
        val events = mutableListOf<Event>()
        val hub = CompanionNowPlayingHub(
            clock = CompanionClock { 0L },
            eventSink = events::add,
        )
        hub.register(
            "lineage",
            CompanionPlaybackMetadata("Title", null, null, null, null),
        )
        hub.unregister("lineage")

        assertEquals(PlaybackStateKind.IDLE, events.last().playbackState?.state)
        assertEquals(null, hub.snapshot())
    }
}

