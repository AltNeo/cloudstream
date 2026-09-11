package com.lagradost.cloudstream3.companion.tv

import com.lagradost.cloudstream3.companion.protocol.Event
import com.lagradost.cloudstream3.companion.protocol.EventKind
import com.lagradost.cloudstream3.companion.protocol.TracksAvailable

/**
 * Main-thread-owned seam for source, audio and subtitle changes. Implementations may delegate
 * to the active player or generator, but must return false when the requested option is absent.
 */
interface CompanionTvTrackSelectionHandler {
    fun selectSource(lineageId: String?, index: Int): Boolean

    fun selectAudio(lineageId: String?, index: Int): Boolean

    fun selectSubtitle(lineageId: String?, index: Int?): Boolean
}

object NoOpCompanionTvTrackSelectionHandler : CompanionTvTrackSelectionHandler {
    override fun selectSource(lineageId: String?, index: Int): Boolean = false

    override fun selectAudio(lineageId: String?, index: Int): Boolean = false

    override fun selectSubtitle(lineageId: String?, index: Int?): Boolean = false
}

/** Publishes a catalog through the normal subscribed EVENT channel. */
fun interface CompanionTvTrackCatalogPublisher {
    fun publish(catalog: TracksAvailable)
}

class CompanionTvEventTrackCatalogPublisher(
    private val sendEvent: (Event) -> Unit,
) : CompanionTvTrackCatalogPublisher {
    override fun publish(catalog: TracksAvailable) {
        sendEvent(Event(kind = EventKind.TRACKS_AVAILABLE, tracksAvailable = catalog))
    }
}
