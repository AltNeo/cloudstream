package com.lagradost.cloudstream3.companion.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val COMPANION_PROTOCOL_VERSION = 3

@Serializable
data class Envelope(
    val v: Int = COMPANION_PROTOCOL_VERSION,
    val id: String,
    val type: MessageType,
    val payload: JsonElement? = null,
)

@Serializable
enum class MessageType {
    PING,
    PLAY,
    PLAYER_CMD,
    KEY,
    INPUT_TEXT,
    OPEN_PAGE,
    SUBSCRIBE,
    UNPAIR,
    SELECT_SOURCE,
    SELECT_AUDIO,
    SELECT_SUBTITLE,
    SYNC_REQUEST,
    SYNC_PUSH,
    RESULT,
    EVENT,
}

@Serializable
enum class LinkType {
    VIDEO,
    M3U8,
    DASH,
}

@Serializable
data class PlayRequest(
    val lineageId: String,
    val attempt: Int,
    val links: List<ResolvedLink>,
    val subtitles: List<ResolvedSubtitle>,
    val title: String,
    val episodeLabel: String? = null,
    val posterUrl: String? = null,
    val mediaId: Int? = null,
    val startPositionMs: Long? = null,
    val durationMs: Long? = null,
)

@Serializable
data class ResolvedLink(
    val url: String,
    val type: LinkType,
    val quality: Int,
    val sourceName: String,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val audioTracks: List<ResolvedAudioTrack> = emptyList(),
    val playlist: List<PlaylistPart>? = null,
    val issuedAtMs: Long,
    val expiresAtMs: Long? = null,
)

@Serializable
data class ResolvedAudioTrack(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class PlaylistPart(
    val url: String,
    val durationUs: Long,
)

@Serializable
data class ResolvedSubtitle(
    val url: String,
    val lang: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class PlayerCommand(
    val action: PlayerAction,
    val positionMs: Long? = null,
    val volume: Float? = null,
)

@Serializable
enum class PlayerAction {
    PLAY,
    PAUSE,
    TOGGLE,
    SEEK_TO,
    SEEK_REL,
    NEXT,
    PREV,
    STOP,
    SET_VOLUME,
}

@Serializable
data class KeyRequest(val keyCode: Int)

@Serializable
data class InputTextRequest(val text: String)

@Serializable
data class OpenPageRequest(val apiName: String, val url: String)

/** Selects one of the source/audio/subtitle options advertised by the TV. */
@Serializable
data class SelectSourceRequest(
    val index: Int,
    val lineageId: String? = null,
)

@Serializable
data class SelectAudioRequest(
    val index: Int,
    val lineageId: String? = null,
)

/** A null index clears the active subtitle track. */
@Serializable
data class SelectSubtitleRequest(
    val index: Int? = null,
    val lineageId: String? = null,
)

@Serializable
enum class ErrorCode {
    NOT_AUTHORIZED,
    INVALID_PAYLOAD,
    NO_ACTIVE_PLAYER,
    UNSUPPORTED,
    INTERNAL,
    CONTROL_DISABLED,
    RATE_LIMITED,
    STALE_REQUEST,
}

@Serializable
data class ResultPayload(
    val ok: Boolean,
    val error: ErrorCode? = null,
    val message: String? = null,
)

@Serializable
enum class EventKind {
    PLAYBACK_STATE,
    INPUT_CONTEXT,
    LINK_FAILED,
    NAV_REQUESTED,
    TRACKS_AVAILABLE,
    SYNC_RECORD,
}

@Serializable
data class Event(
    val kind: EventKind,
    val playbackState: PlaybackState? = null,
    val inputContext: InputContext? = null,
    val linkFailed: LinkFailed? = null,
    val navRequested: NavRequested? = null,
    val tracksAvailable: TracksAvailable? = null,
    val syncRecord: SyncRecordPayload? = null,
)

@Serializable
data class SyncRequestPayload(
    val accountNamespace: String,
    val field: SyncField,
    val mediaId: Int,
)

@Serializable
data class SyncRecordPayload(
    val accountNamespace: String,
    val field: SyncField,
    val mediaId: Int,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val watchState: SyncWatchState? = null,
    val updatedAtMs: Long,
)

@Serializable
enum class SyncField {
    VIDEO_POS_DUR,
    VIDEO_WATCH_STATE,
}

@Serializable
enum class SyncWatchState {
    WATCHED,
}

@Serializable
data class TrackOption(
    val index: Int,
    val label: String,
    val language: String? = null,
    val quality: Int? = null,
    val sourceName: String? = null,
)

@Serializable
data class TracksAvailable(
    val lineageId: String? = null,
    val sources: List<TrackOption> = emptyList(),
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitles: List<TrackOption> = emptyList(),
    val selectedSourceIndex: Int? = null,
    val selectedAudioIndex: Int? = null,
    val selectedSubtitleIndex: Int? = null,
)

@Serializable
data class PlaybackState(
    val lineageId: String? = null,
    val title: String,
    val episodeLabel: String? = null,
    val posterUrl: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val state: PlaybackStateKind,
    val mediaId: Int? = null,
)

@Serializable
enum class PlaybackStateKind {
    PLAYING,
    PAUSED,
    BUFFERING,
    ENDED,
    IDLE,
}

@Serializable
data class InputContext(
    val context: InputContextKind,
    val currentText: String? = null,
)

@Serializable
enum class InputContextKind {
    SEARCH_FIELD,
    IDLE,
}

@Serializable
data class LinkFailed(
    val lineageId: String,
    val attempt: Int,
    val linkIndex: Int,
    val httpStatus: Int? = null,
    val stage: LinkFailureStage,
)

@Serializable
enum class LinkFailureStage {
    MANIFEST,
    SEGMENT,
    AUTH,
    UNKNOWN,
}

@Serializable
enum class NavigationDirection {
    NEXT,
    PREV,
}

@Serializable
data class NavRequested(
    val lineageId: String,
    val direction: NavigationDirection,
)
