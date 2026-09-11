package com.lagradost.cloudstream3.companion.phone

import com.lagradost.cloudstream3.companion.protocol.TrackOption
import com.lagradost.cloudstream3.companion.protocol.TracksAvailable

data class PhoneTrackSelectionSnapshot(
    val catalog: TracksAvailable? = null,
)

/** Stores the latest catalog while rejecting events from a superseded playback lineage. */
class PhoneTrackSelectionState {
    @Volatile
    private var current = PhoneTrackSelectionSnapshot()

    fun snapshot(): PhoneTrackSelectionSnapshot = current

    @Synchronized
    fun update(catalog: TracksAvailable): Boolean {
        val previousLineage = current.catalog?.lineageId
        val nextLineage = catalog.lineageId
        if (previousLineage != null && nextLineage != null && previousLineage != nextLineage) {
            return false
        }
        current = PhoneTrackSelectionSnapshot(catalog)
        return true
    }

    @Synchronized
    fun clear(lineageId: String? = null): Boolean {
        val currentLineage = current.catalog?.lineageId
        if (lineageId != null && currentLineage != null && lineageId != currentLineage) {
            return false
        }
        current = PhoneTrackSelectionSnapshot()
        return true
    }

    fun source(index: Int): TrackOption? =
        current.catalog?.sources?.firstOrNull { it.index == index }

    fun audio(index: Int): TrackOption? =
        current.catalog?.audioTracks?.firstOrNull { it.index == index }

    fun subtitle(index: Int): TrackOption? =
        current.catalog?.subtitles?.firstOrNull { it.index == index }
}

/** Phone-facing adapter for rendering catalogs and sending validated selection intents. */
class CompanionPhoneTrackSelectionAdapter(
    private val session: PhoneCompanionSession,
    val state: PhoneTrackSelectionState = PhoneTrackSelectionState(),
    private val onCatalogChanged: (TracksAvailable?) -> Unit = {},
) {
    fun accept(catalog: TracksAvailable): Boolean {
        val accepted = state.update(catalog)
        if (accepted) onCatalogChanged(catalog)
        return accepted
    }

    fun clear(lineageId: String? = null): Boolean {
        val cleared = state.clear(lineageId)
        if (cleared) onCatalogChanged(null)
        return cleared
    }

    suspend fun selectSource(index: Int): Boolean {
        val catalog = state.snapshot().catalog ?: return false
        return state.source(index)?.let { session.selectSource(it.index, catalog.lineageId) } ?: false
    }

    suspend fun selectAudio(index: Int): Boolean {
        val catalog = state.snapshot().catalog ?: return false
        return state.audio(index)?.let { session.selectAudio(it.index, catalog.lineageId) } ?: false
    }

    suspend fun selectSubtitle(index: Int?): Boolean {
        val catalog = state.snapshot().catalog ?: return false
        return if (index == null || state.subtitle(index) != null) {
            session.selectSubtitle(
                index,
                catalog.lineageId,
            )
        } else {
            false
        }
    }
}
