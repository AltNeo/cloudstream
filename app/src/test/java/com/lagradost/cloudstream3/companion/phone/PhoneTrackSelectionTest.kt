package com.lagradost.cloudstream3.companion.phone

import com.lagradost.cloudstream3.companion.protocol.TrackOption
import com.lagradost.cloudstream3.companion.protocol.TracksAvailable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTrackSelectionTest {
    @Test
    fun `state rejects catalogs from another lineage`() {
        val state = PhoneTrackSelectionState()
        assertTrue(state.update(TracksAvailable(lineageId = "one")))
        assertFalse(state.update(TracksAvailable(lineageId = "two")))
        assertTrue(state.snapshot().catalog?.lineageId == "one")
    }

    @Test
    fun `state exposes only advertised option indices`() {
        val state = PhoneTrackSelectionState()
        state.update(
            TracksAvailable(
                lineageId = "one",
                sources = listOf(TrackOption(7, "source")),
                audioTracks = listOf(TrackOption(3, "English")),
                subtitles = listOf(TrackOption(5, "English")),
            ),
        )
        assertNotNull(state.source(7))
        assertNotNull(state.audio(3))
        assertNotNull(state.subtitle(5))
        assertTrue(state.source(0) == null)
    }

    @Test
    fun `clear ignores stale lineage`() {
        val state = PhoneTrackSelectionState()
        state.update(TracksAvailable(lineageId = "one"))
        assertFalse(state.clear("two"))
        assertNotNull(state.snapshot().catalog)
        assertTrue(state.clear("one"))
        assertTrue(state.snapshot().catalog == null)
    }
}
