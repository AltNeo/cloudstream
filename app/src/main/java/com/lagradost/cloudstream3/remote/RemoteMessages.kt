package com.lagradost.cloudstream3.remote

import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/**
 * Protocol v2 message types.
 *
 * Unauthenticated: [PING], [PAIR_HELLO], [PAIR_VERIFY]
 * Authenticated one-shot: [UNPAIR], [HELLO], [LAUNCH], [KEY], [TEXT], [PLAY],
 * [PLAYER_CMD], [GET_STATE], [OPEN_PAGE], [SYNC_EXTENSIONS], [EXT_FILE_START],
 * [EXT_FILE_CHUNK], [EXT_FILE_END], [SYNC_LIBRARY]
 * Event channel: [SUBSCRIBE] (request), [EVENT] (TV -> phone)
 */
@Serializable
enum class RemoteMessageType {
    PING, PAIR_HELLO, PAIR_VERIFY,
    UNPAIR, HELLO, LAUNCH, KEY, TEXT,
    PLAY, PLAYER_CMD, GET_STATE, OPEN_PAGE,
    SYNC_EXTENSIONS, EXT_FILE_START, EXT_FILE_CHUNK, EXT_FILE_END,
    SYNC_LIBRARY,
    SUBSCRIBE, EVENT,
}

/**
 * Every frame is this envelope. [auth] = Base64(HMAC-SHA256(token, "$requestId:$timestampMs")).
 * [auth] and [deviceId] are required for every type except [RemoteMessageType.PING] and
 * the PAIR_* types. [payload] is decoded per [type].
 */
@Serializable
data class RemoteEnvelope(
    val version: Int = 2,
    val requestId: String = UUID.randomUUID().toString(),
    val deviceId: String = "",
    val timestampMs: Long = 0L,
    val auth: String? = null,
    val type: RemoteMessageType,
    val payload: JsonObject? = null,
)

@Serializable
data class RemoteReply(
    val version: Int = 2,
    val requestId: String,
    val accepted: Boolean,
    val error: String? = null,
    val payload: JsonObject? = null,
)

// ---------------------------------------------------------------------------
// Payloads
// ---------------------------------------------------------------------------

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val appVersion: String,
    val protocol: Int,
    val isTv: Boolean,
    val paired: Boolean,
    val capabilities: Set<String> = emptySet(),
    /** sha256 over sorted "internalName@version@repoUrl" of the installed online plugins, null if unknown */
    val pluginSetHash: String? = null,
    /** sha256 over the local library dump (key@value@ts), null when the library is empty */
    val librarySetHash: String? = null,
) {
    companion object {
        const val CAP_EVENTS = "events"
        const val CAP_EXT_SYNC = "ext-sync"
        const val CAP_LIB_SYNC = "lib-sync"
        const val CAP_PLAYER_CMD = "player-cmd"
    }
}

@Serializable
data class PairHelloRequest(
    val deviceId: String,
    val deviceName: String,
)

@Serializable
data class PairHelloReply(
    val pairingSessionId: String,
    val expiresInMs: Long,
)

@Serializable
data class PairVerifyRequest(
    val pairingSessionId: String,
    val pin: String,
)

@Serializable
data class PairVerifyReply(
    val token: String,
    val tv: DeviceInfo,
)

@Serializable
data class KeyPayload(val keyCode: Int)

@Serializable
data class TextPayload(val text: String)

@Serializable
data class PlayPayload(
    val links: List<CloudStreamPackage.MinimalVideoLink> = emptyList(),
    val subtitles: List<CloudStreamPackage.MinimalSubtitleLink> = emptyList(),
    val title: String? = null,
    val poster: String? = null,
    val mediaId: Int? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
)

@Serializable
data class PlayerCmdPayload(
    val action: Action,
    val positionMs: Long? = null,
    val deltaMs: Long? = null,
    val speed: Float? = null,
) {
    @Serializable
    enum class Action {
        PAUSE, RESUME, PLAY_PAUSE, SEEK_TO, SEEK_BY, STOP,
        SET_SPEED, VOLUME_UP, VOLUME_DOWN, MUTE,
    }
}

@Serializable
data class OpenPagePayload(val apiName: String, val url: String)

@Serializable
data class ExtensionSyncPayload(
    val repos: List<RepositoryData> = emptyList(),
    val plugins: List<SyncedPlugin> = emptyList(),
)

@Serializable
data class SyncedPlugin(
    val internalName: String,
    val url: String,
    val repositoryUrl: String,
    val version: Int,
    val fileHash: String? = null,
    val displayName: String,
)

@Serializable
data class ExtensionSyncReply(
    val results: List<PluginSyncResult> = emptyList(),
)

@Serializable
data class PluginSyncResult(
    val internalName: String,
    val status: Status,
    val message: String? = null,
) {
    @Serializable
    enum class Status {
        OK_INSTALLED, OK_ALREADY, UPDATED, REMOVED,
        NEWER_KEPT, DOWNLOAD_ONLY_SAFE_MODE, FAILED,
    }
}

@Serializable
data class LibrarySyncPayload(
    val full: Boolean,
    val entries: List<LibraryEntry> = emptyList(),
)

/**
 * [key] is the *unprefixed* DataStore key ("$KEY/$id", e.g. "video_pos_dur/123").
 * [valueJson] is the raw stored JSON literal (what DataStore.setKey writes); null = tombstone.
 */
@Serializable
data class LibraryEntry(
    val key: String,
    val valueJson: String?,
    val updatedAtMs: Long,
)

@Serializable
data class RemoteEvent(
    val kind: Kind,
    val nowPlaying: NowPlayingPayload? = null,
    val libraryDelta: LibrarySyncPayload? = null,
    val pluginSync: PluginSyncResult? = null,
) {
    @Serializable
    enum class Kind { PLAYBACK_STATE, PLAYER_GONE, LIBRARY_DELTA, PLUGIN_SYNC_STATUS, PAIRING_STARTED }
}

@Serializable
data class NowPlayingPayload(
    val mediaId: Int? = null,
    val title: String? = null,
    val episodeName: String? = null,
    val poster: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val state: State = State.IDLE,
    val speed: Float = 1f,
) {
    @Serializable
    enum class State { PLAYING, PAUSED, BUFFERING, ENDED, IDLE }
}

@Serializable
data class ExtFileStartPayload(
    val internalName: String,
    val repositoryUrl: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class ExtFileChunkPayload(
    val internalName: String,
    val seq: Int,
    val dataB64: String,
)

@Serializable
data class ExtFileEndPayload(val internalName: String)

// ---------------------------------------------------------------------------
// Envelope <-> payload helpers
// ---------------------------------------------------------------------------

inline fun <reified T> encodePayload(value: T): JsonObject {
    val json = LanRemoteProtocol.json
    return json.parseToJsonElement(json.encodeToString(value)).jsonObject
}

inline fun <reified T> RemoteEnvelope.payloadAs(): T? =
    payload?.let { LanRemoteProtocol.json.decodeFromString<T>(it.toString()) }

inline fun <reified T> RemoteReply.payloadAs(): T? =
    payload?.let { LanRemoteProtocol.json.decodeFromString<T>(it.toString()) }

/** Decode a raw payload JsonObject (used by handlers that already extracted it). */
inline fun <reified T> JsonObject?.payloadAs(): T? =
    this?.let { LanRemoteProtocol.json.decodeFromString<T>(it.toString()) }
