package com.lagradost.cloudstream3.companion.ui

import com.lagradost.cloudstream3.companion.protocol.TracksAvailable
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Narrow UI seam for the companion core. The transport owns pairing and playback; UI only
 * observes immutable state and sends user intent through this object. The default implementation
 * is inert, which preserves upstream behaviour when the companion feature is unused.
 */
object CompanionUiBridge {
    data class Device(
        val id: String,
        val name: String,
        val address: String? = null,
        val paired: Boolean = false,
        val connected: Boolean = false,
    )

    data class Playback(
        val title: String,
        val episodeLabel: String? = null,
        val posterUrl: String? = null,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val playing: Boolean = false,
        val inputContext: InputContext = InputContext.IDLE,
        val remotePhoneName: String? = null,
    )

    enum class InputContext { SEARCH_FIELD, IDLE }

    data class Pairing(
        val pin: String? = null,
        val expiresAtMs: Long? = null,
        val pendingDeviceId: String? = null,
        val pendingDeviceName: String? = null,
    )

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _playback = MutableStateFlow<Playback?>(null)
    val playback: StateFlow<Playback?> = _playback.asStateFlow()

    private val _pairing = MutableStateFlow<Pairing?>(null)
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    private val _tracks = MutableStateFlow<TracksAvailable?>(null)
    val tracks: StateFlow<TracksAvailable?> = _tracks.asStateFlow()

    @Volatile
    private var remotePhoneName: String? = null

    @Volatile
    private var actions: Actions = NoopActions

    interface Actions {
        fun setEnabled(enabled: Boolean)
        fun approvePairing()
        fun rejectPairing()
        fun pairTv(address: String?, pin: String?)
        fun connectTv(deviceId: String)
        fun revoke(deviceId: String)
        fun startPairing()
        fun stopPairing()
        fun playOnTv(
            title: String,
            episodeLabel: String?,
            posterUrl: String?,
            mediaId: Int?,
            links: List<ExtractorLink>,
            subtitles: List<SubtitleData>,
        ): Boolean

        fun sendPlayerCommand(action: String, positionMs: Long? = null)
        fun sendKey(keyCode: Int)
        fun sendInputText(text: String)
    }

    private object NoopActions : Actions {
        override fun setEnabled(enabled: Boolean) = Unit
        override fun approvePairing() = Unit
        override fun rejectPairing() = Unit
        override fun pairTv(address: String?, pin: String?) = Unit
        override fun connectTv(deviceId: String) = Unit
        override fun revoke(deviceId: String) = Unit
        override fun startPairing() = Unit
        override fun stopPairing() = Unit
        override fun playOnTv(
            title: String,
            episodeLabel: String?,
            posterUrl: String?,
            mediaId: Int?,
            links: List<ExtractorLink>,
            subtitles: List<SubtitleData>,
        ): Boolean = false

        override fun sendPlayerCommand(action: String, positionMs: Long?) {
            CompanionPlayerController.dispatch(action, positionMs)
        }
        override fun sendKey(keyCode: Int) = Unit
        override fun sendInputText(text: String) = Unit
    }

    fun install(actions: Actions, initialDevices: List<Device> = emptyList()) {
        this.actions = actions
        _devices.value = initialDevices
    }

    fun publishDevices(devices: List<Device>) {
        _devices.value = devices
    }

    fun publishPlayback(playback: Playback?) {
        _playback.value = playback?.copy(remotePhoneName = playback.remotePhoneName ?: remotePhoneName)
    }

    fun setRemotePhoneName(name: String?) {
        remotePhoneName = name
        _playback.value = _playback.value?.copy(remotePhoneName = name)
    }

    fun publishPairing(pairing: Pairing?) {
        _pairing.value = pairing
    }

    fun publishTracks(catalog: TracksAvailable?) {
        _tracks.value = catalog
    }

    fun pairTv(address: String? = null, pin: String? = null) = actions.pairTv(address, pin)
    fun connectTv(deviceId: String) = actions.connectTv(deviceId)
    fun setEnabled(enabled: Boolean) = actions.setEnabled(enabled)
    fun approvePairing() = actions.approvePairing()
    fun rejectPairing() = actions.rejectPairing()
    fun revoke(deviceId: String) = actions.revoke(deviceId)
    fun startPairing() = actions.startPairing()
    fun stopPairing() = actions.stopPairing()

    fun playOnTv(
        title: String,
        episodeLabel: String?,
        posterUrl: String?,
        mediaId: Int?,
        links: List<ExtractorLink>,
        subtitles: List<SubtitleData>,
    ): Boolean = actions.playOnTv(title, episodeLabel, posterUrl, mediaId, links, subtitles)

    fun sendPlayerCommand(action: String, positionMs: Long? = null) =
        actions.sendPlayerCommand(action, positionMs)

    fun sendKey(keyCode: Int) = actions.sendKey(keyCode)
    fun sendInputText(text: String) = actions.sendInputText(text)
}
