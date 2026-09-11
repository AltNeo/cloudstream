package com.lagradost.cloudstream3.companion.ui

import com.lagradost.cloudstream3.companion.protocol.TracksAvailable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-neutral bridge for a source/audio/subtitle picker. */
class CompanionTrackSelectionBridge {
    private val _tracks = MutableStateFlow<TracksAvailable?>(null)
    val tracks: StateFlow<TracksAvailable?> = _tracks.asStateFlow()

    fun publish(catalog: TracksAvailable) {
        _tracks.value = catalog
    }

    fun clear() {
        _tracks.value = null
    }
}
