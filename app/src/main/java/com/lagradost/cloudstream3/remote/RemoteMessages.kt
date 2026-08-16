package com.lagradost.cloudstream3.remote

import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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
 *
 * [INPUT_TEXT] is a phone -> TV whole-string replacement of the currently focused editable
 * text view (see [InputTextPayload]); the TV advertises support via [DeviceInfo.CAP_INPUT_TEXT].
 *
 * [SELECT_TRACK] is a phone -> TV renderer track selection (video / audio by wire id, and
 * subtitle *disable* only - text tracks are never claimed by wire id, see [SelectTrackPayload]);
 * the TV advertises support via [DeviceInfo.CAP_TRACKS].
 *
 * [UNKNOWN] is never produced locally; it is only the fallback for a value this peer does
 * not know yet, so an envelope from a newer phone still decodes instead of killing the
 * socket (ignoreUnknownKeys tolerates unknown fields, not unknown enum names).
 */
@Serializable(with = RemoteMessageTypeSerializer::class)
enum class RemoteMessageType {
    PING, PAIR_HELLO, PAIR_VERIFY,
    UNPAIR, HELLO, LAUNCH, KEY, TEXT,
    PLAY, PLAYER_CMD, GET_STATE, OPEN_PAGE,
    SYNC_EXTENSIONS, EXT_FILE_START, EXT_FILE_CHUNK, EXT_FILE_END,
    SYNC_LIBRARY,
    SUBSCRIBE, EVENT,
    INPUT_TEXT,
    SELECT_TRACK, SELECT_PLAYBACK_OPTION,
    UNKNOWN,
}

/**
 * String-backed enum serializer that maps unrecognized wire names to [unknown] instead of
 * throwing, so a newer peer's values never break an older peer's socket read loop. The
 * fallback is decode-only: encoding [unknown] is refused because emitting the literal
 * "UNKNOWN" would corrupt an older peer's view of the value.
 */
private fun <T : Enum<T>> lenientEnumSerializer(
    serialName: String,
    entries: Array<T>,
    unknown: T,
): KSerializer<T> = object : KSerializer<T> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) {
        if (value == unknown) {
            throw SerializationException(
                "Refusing to encode $serialName.UNKNOWN: the lenient fallback must never reach the wire"
            )
        }
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): T {
        val name = decoder.decodeString()
        return entries.firstOrNull { it.name == name } ?: unknown
    }
}

object RemoteMessageTypeSerializer : KSerializer<RemoteMessageType> by lenientEnumSerializer(
    serialName = "RemoteMessageType",
    entries = RemoteMessageType.entries.toTypedArray(),
    unknown = RemoteMessageType.UNKNOWN,
)

/**
 * Every logical frame is this envelope. [auth] is a HMAC over the complete canonical envelope
 * (version, deviceId, requestId, timestampMs, type, and payload). Paired traffic is carried in an
 * AES-GCM [EncryptedRemoteFrame]; pairing discovery frames remain unencrypted.
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
) {
    constructor(requestId: String, accepted: Boolean, error: String? = null) :
        this(2, requestId, accepted, error, null)
}

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
        const val CAP_INPUT_TEXT = "input-text"
        const val CAP_INPUT_CONTEXT = "input-context"
        const val CAP_TRACKS = "tracks"
        const val CAP_PLAYBACK_CHOICES = "playback-choices"
    }
}

@Serializable
data class PairHelloRequest(
    val deviceId: String,
    val deviceName: String,
    val publicKey: String = "",
)

@Serializable
data class PairHelloReply(
    val pairingSessionId: String,
    val expiresInMs: Long,
    val publicKey: String = "",
)

@Serializable
data class PairVerifyRequest(
    val pairingSessionId: String,
    /** HMAC proof under the ECDH + PIN-derived pairing key; the PIN never crosses the LAN. */
    val proof: String,
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

/**
 * Capabilities the phone advertises when it opens the SUBSCRIBE event channel. The TV keeps
 * them per subscriber and only streams opt-in kinds (INPUT_CONTEXT) to phones that asked.
 * A legacy phone sends no payload, which decodes to an empty set -> it never sees new kinds.
 */
@Serializable
data class SubscribePayload(
    val capabilities: Set<String> = emptySet(),
)

/**
 * TV -> phone notification that a text-entry surface changed state. [currentText] is the
 * whole current string (the phone replaces, never edits); [label] is a UI hint such as
 * "Search". IDLE means no text entry is active and the phone should drop its input UI.
 */
@Serializable
data class InputContextPayload(
    val context: Context = Context.IDLE,
    val currentText: String? = null,
    val label: String? = null,
) {
    @Serializable(with = InputContextSerializer::class)
    enum class Context { SEARCH_FIELD, URL_BAR, IDLE, UNKNOWN }
}

object InputContextSerializer : KSerializer<InputContextPayload.Context> by lenientEnumSerializer(
    serialName = "InputContextPayload.Context",
    entries = InputContextPayload.Context.entries.toTypedArray(),
    unknown = InputContextPayload.Context.UNKNOWN,
)

/** Whole-string replacement for the TV's focused editable field, including an empty clear. */
@Serializable
data class InputTextPayload(val text: String)

/**
 * Whole-string input bound, shared by TV (apply INPUT_TEXT / echo) and phone (send gate).
 *
 * Two gates, both all-or-nothing (an oversized string is rejected before EditText.setText,
 * before the INPUT_CONTEXT echo, and before the phone puts it on the wire - never truncated,
 * which could split a surrogate pair or combining sequence):
 *  - [MAX_INPUT_TEXT_LENGTH]: code-unit cap, sized for the common case (plain text, CJK,
 *    emoji). It is NOT frame-safe by itself: a pathological string of control characters
 *    expands ~6x under JSON escaping.
 *  - [serializedInputTextBytes] vs [MAX_INPUT_TEXT_PAYLOAD_BYTES]: the hard serialized-frame
 *    guarantee. kotlinx.serialization escapes `"` -> `\"`, `\` -> `\\`, and every control
 *    character U+0000..U+001F as `\uXXXX` (6 bytes), so a string is accepted only when its
 *    worst-case JSON-escaped UTF-8 size still fits the 1 MiB frame cap together with the
 *    envelope.
 */
const val MAX_INPUT_TEXT_LENGTH = 262_144

/**
 * Serialized-frame budget for the INPUT_TEXT payload value (2 surrounding quotes + JSON-escaped
 * text). Leaves generous headroom under [LanRemoteProtocol.MAX_FRAME_BYTES] for the envelope
 * (requestId, deviceId, timestampMs, auth, type + payload wrapper keys, ~240 B worst case).
 */
const val MAX_INPUT_TEXT_PAYLOAD_BYTES = 1_000_000

/**
 * Worst-case UTF-8 size of [text] as kotlinx.serialization emits it inside a JSON string
 * (per UTF-16 code unit): control chars U+0000..U+001F -> `\uXXXX` (6 B), `"`/`\` -> 2 B,
 * ASCII -> 1 B, BMP -> 2-3 B, surrogate units -> 3 B each. Astral pairs are deliberately
 * over-counted (3+3 B vs the real 4 B for the pair) so the result is always a safe upper
 * bound. Includes the 2 surrounding quotes.
 */
fun serializedInputTextBytes(text: String): Int {
    var size = 2 // surrounding quotes
    for (c in text) {
        size += when {
            c.code < 0x20 -> 6 // \uXXXX
            c == '"' || c == '\\' -> 2 // \" or \\
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            else -> 3
        }
    }
    return size
}

/** True when [text] fits the whole-string input bound (code units AND serialized frame). */
fun isInputTextWithinBound(text: String): Boolean =
    text.length <= MAX_INPUT_TEXT_LENGTH &&
        serializedInputTextBytes(text) <= MAX_INPUT_TEXT_PAYLOAD_BYTES

/**
 * Pure whole-string INPUT_TEXT send policy, shared by the debounced sender (F1) and the
 * explicit flush: a replacement is sent only when it differs from the TV's last echoed
 * [echoedCurrentText] (the send/echo feedback-loop guard) and fits the whole-string bound
 * (all-or-nothing, never truncated/split). An empty string clears the field when the TV has
 * not echoed an empty currentText yet.
 */
fun shouldSendInputText(text: String, echoedCurrentText: String?): Boolean =
    isInputTextWithinBound(text) && text != echoedCurrentText

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
    @Serializable(with = PlayerCmdActionSerializer::class)
    enum class Action {
        PAUSE, RESUME, PLAY_PAUSE, SEEK_TO, SEEK_BY, STOP,
        SET_SPEED, VOLUME_UP, VOLUME_DOWN, MUTE,
        UNKNOWN,
    }
}

object PlayerCmdActionSerializer : KSerializer<PlayerCmdPayload.Action> by lenientEnumSerializer(
    serialName = "PlayerCmdPayload.Action",
    entries = PlayerCmdPayload.Action.entries.toTypedArray(),
    unknown = PlayerCmdPayload.Action.UNKNOWN,
)

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
    @Serializable(with = PluginSyncStatusSerializer::class)
    enum class Status {
        OK_INSTALLED, OK_ALREADY, UPDATED, REMOVED,
        NEWER_KEPT, DOWNLOAD_ONLY_SAFE_MODE, FAILED,
        UNKNOWN,
    }
}

object PluginSyncStatusSerializer : KSerializer<PluginSyncResult.Status> by lenientEnumSerializer(
    serialName = "PluginSyncResult.Status",
    entries = PluginSyncResult.Status.entries.toTypedArray(),
    unknown = PluginSyncResult.Status.UNKNOWN,
)

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
    val inputContext: InputContextPayload? = null,
    val tracks: TracksPayload? = null,
) {
    @Serializable(with = RemoteEventKindSerializer::class)
    enum class Kind { PLAYBACK_STATE, PLAYER_GONE, LIBRARY_DELTA, PLUGIN_SYNC_STATUS, PAIRING_STARTED, INPUT_CONTEXT, TRACKS_AVAILABLE, UNKNOWN }
}

object RemoteEventKindSerializer : KSerializer<RemoteEvent.Kind> by lenientEnumSerializer(
    serialName = "RemoteEvent.Kind",
    entries = RemoteEvent.Kind.entries.toTypedArray(),
    unknown = RemoteEvent.Kind.UNKNOWN,
)

@Serializable
data class NowPlayingPayload(
    val mediaId: Int? = null,
    val title: String? = null,
    val episodeName: String? = null,
    val streamName: String? = null,
    val poster: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val state: State = State.IDLE,
    val speed: Float = 1f,
) {
    @Serializable(with = NowPlayingStateSerializer::class)
    enum class State { PLAYING, PAUSED, BUFFERING, ENDED, IDLE, UNKNOWN }
}

object NowPlayingStateSerializer : KSerializer<NowPlayingPayload.State> by lenientEnumSerializer(
    serialName = "NowPlayingPayload.State",
    entries = NowPlayingPayload.State.entries.toTypedArray(),
    unknown = NowPlayingPayload.State.UNKNOWN,
)

// ---------------------------------------------------------------------------
// F4a: renderer tracks (video/audio selection + subtitle disable, no source switching)
// ---------------------------------------------------------------------------

/**
 * Snapshot of the active player's renderer tracks, TV -> phone (event kind TRACKS_AVAILABLE).
 *
 * Only [videoTracks]/[audioTracks] are selectable by wire id ([SelectTrackPayload]); text
 * tracks are deliberately NOT carried here (no ids, no labels): the IPlayer contract selects
 * subtitles by [SubtitleData], not by renderer track id, so the phone can only *disable*
 * subtitles (see [subtitlesEnabled]/[hasTextTracks]). Source switching is out of scope (F4b).
 */
@Serializable
data class TracksPayload(
    val videoTracks: List<TrackInfo> = emptyList(),
    val audioTracks: List<TrackInfo> = emptyList(),
    val currentVideoId: String? = null,
    val currentAudioId: String? = null,
    /** True while at least one text track is active (subtitles shown). */
    val subtitlesEnabled: Boolean = false,
    /** True when the player exposes any text tracks at all (so the disable affordance may show). */
    val hasTextTracks: Boolean = false,
    /** Direct source links sent with the companion PLAY request (F4b). */
    val sources: List<PlaybackChoice> = emptyList(),
    val currentSourceIndex: Int = 0,
    /** External subtitle files sent with the companion PLAY request. */
    val subtitles: List<PlaybackChoice> = emptyList(),
    /** Null means subtitles are off. */
    val currentSubtitleIndex: Int? = null,
)

@Serializable
data class PlaybackChoice(
    val index: Int,
    val label: String,
    val detail: String? = null,
)

@Serializable
data class TrackInfo(
    val id: String,
    val label: String,
    val language: String? = null,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val formatIndex: Int? = null,
    /** Human hint such as "1280x720" (video) or "Stereo • AAC" (audio). */
    val extra: String? = null,
)

/**
 * Phone -> TV renderer track selection. [id] selects the video/audio track with that wire id;
 * for [TrackType.TEXT] [id] must be null because text tracks are never claimed by wire id - a
 * null id means "disable subtitles". The UNKNOWN fallback is decode-only and rejected by the
 * TV gate ([CommandHandlers.unsupportedSelectTrack]).
 */
@Serializable
data class SelectTrackPayload(
    val type: TrackType = TrackType.UNKNOWN,
    val id: String? = null,
) {
    @Serializable(with = SelectTrackTypeSerializer::class)
    enum class TrackType { VIDEO, AUDIO, TEXT, UNKNOWN }
}

object SelectTrackTypeSerializer : KSerializer<SelectTrackPayload.TrackType> by lenientEnumSerializer(
    serialName = "SelectTrackPayload.TrackType",
    entries = SelectTrackPayload.TrackType.entries.toTypedArray(),
    unknown = SelectTrackPayload.TrackType.UNKNOWN,
)

/** Selects a companion source or external subtitle. A null subtitle index disables subtitles. */
@Serializable
data class SelectPlaybackOptionPayload(
    val type: Type = Type.UNKNOWN,
    val index: Int? = null,
) {
    @Serializable(with = SelectPlaybackOptionTypeSerializer::class)
    enum class Type { SOURCE, SUBTITLE, UNKNOWN }
}

object SelectPlaybackOptionTypeSerializer : KSerializer<SelectPlaybackOptionPayload.Type> by lenientEnumSerializer(
    serialName = "SelectPlaybackOptionPayload.Type",
    entries = SelectPlaybackOptionPayload.Type.entries.toTypedArray(),
    unknown = SelectPlaybackOptionPayload.Type.UNKNOWN,
)

@Serializable
data class ExtFileStartPayload(
    val transferId: String,
    val internalName: String,
    val repositoryUrl: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class ExtFileChunkPayload(
    val transferId: String,
    val seq: Int,
    val dataB64: String,
)

@Serializable
data class ExtFileEndPayload(val transferId: String)

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
