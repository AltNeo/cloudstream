package com.lagradost.cloudstream3.remote.server

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.remote.DeviceInfo
import com.lagradost.cloudstream3.remote.ExtFileChunkPayload
import com.lagradost.cloudstream3.remote.ExtFileEndPayload
import com.lagradost.cloudstream3.remote.ExtFileStartPayload
import com.lagradost.cloudstream3.remote.ExtensionSyncReply
import com.lagradost.cloudstream3.remote.InputTextPayload
import com.lagradost.cloudstream3.remote.KeyPayload
import com.lagradost.cloudstream3.remote.LanRemoteProtocol
import com.lagradost.cloudstream3.remote.LibrarySyncPayload
import com.lagradost.cloudstream3.remote.OpenPagePayload
import com.lagradost.cloudstream3.remote.PairingManager
import com.lagradost.cloudstream3.remote.PlayPayload
import com.lagradost.cloudstream3.remote.PlayerCmdPayload
import com.lagradost.cloudstream3.remote.RemoteEnvelope
import com.lagradost.cloudstream3.remote.RemoteMessageType
import com.lagradost.cloudstream3.remote.RemoteReply
import com.lagradost.cloudstream3.remote.SelectTrackPayload
import com.lagradost.cloudstream3.remote.SelectPlaybackOptionPayload
import com.lagradost.cloudstream3.remote.TextPayload
import com.lagradost.cloudstream3.remote.encodePayload
import com.lagradost.cloudstream3.remote.isInputTextWithinBound
import com.lagradost.cloudstream3.remote.payloadAs
import com.lagradost.cloudstream3.remote.sync.ExtensionSyncManager
import com.lagradost.cloudstream3.remote.sync.LibrarySyncManager
import com.lagradost.cloudstream3.ui.player.PlaybackCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Per-type handlers for authenticated one-shot v2 commands (plan §5.1).
 * The auth gate lives in LanRemoteServer; everything reaching here is a known,
 * HMAC-verified device.
 */
object CommandHandlers {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val allowedKeyCodes = setOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_DEL,
    )

    suspend fun handle(context: Context, envelope: RemoteEnvelope): RemoteReply {
        val payload = envelope.payload
        return when (envelope.type) {
            RemoteMessageType.HELLO -> ok(
                envelope,
                encodePayload(buildDeviceInfo(context, paired = true)),
            )

            RemoteMessageType.LAUNCH -> {
                mainHandler.post { launchApp(context) }
                ok(envelope)
            }

            RemoteMessageType.KEY -> {
                val key = payload.payloadAs<KeyPayload>()
                val keyCode = key?.keyCode
                if (keyCode == null || keyCode !in allowedKeyCodes) {
                    err(envelope, "Unsupported key")
                } else {
                    mainHandler.post { dispatchKey(context, keyCode) }
                    ok(envelope)
                }
            }

            RemoteMessageType.TEXT -> {
                val text = payload.payloadAs<TextPayload>()?.text
                    ?.takeIf { it.isNotBlank() }?.take(256)
                if (text == null) {
                    err(envelope, "Text is empty")
                } else {
                    mainHandler.post { dispatchText(text) }
                    ok(envelope)
                }
            }

            RemoteMessageType.INPUT_TEXT -> {
                val text = payload.payloadAs<InputTextPayload>()?.text
                if (text == null) {
                    err(envelope, "Invalid payload")
                } else if (!isInputTextWithinBound(text)) {
                    // Whole-string bound before EditText.setText: reject (accepted=false)
                    // rather than truncating/splitting, so an oversized authenticated
                    // INPUT_TEXT never reaches the focused view or a frame over the 1 MiB cap.
                    err(envelope, "Input too large")
                } else {
                    // Whole-string replacement must inspect and mutate the focused view on
                    // the main thread, and report success so the phone can surface failures.
                    val applied = withContext(Dispatchers.Main) { applyInputText(text) }
                    if (applied) ok(envelope) else err(envelope, "No editable text field focused")
                }
            }

            RemoteMessageType.PLAY -> {
                val play = payload.payloadAs<PlayPayload>()
                val safePlay = play?.copy(
                    links = PlaybackCoordinator.tvCompatibleLinks(play.links),
                )
                if (safePlay == null || safePlay.links.isEmpty()) {
                    err(envelope, "No playable links")
                } else {
                    PendingCommandQueue.submit(
                        RemoteMessageType.PLAY,
                        encodePayload(safePlay),
                        context,
                    )
                    ok(envelope)
                }
            }

            RemoteMessageType.OPEN_PAGE -> {
                val page = payload.payloadAs<OpenPagePayload>()
                if (page == null || page.apiName.isBlank() || page.url.isBlank()) {
                    err(envelope, "Invalid page")
                } else {
                    PendingCommandQueue.submit(RemoteMessageType.OPEN_PAGE, payload, context)
                    ok(envelope)
                }
            }

            RemoteMessageType.PLAYER_CMD -> {
                val cmd = payload.payloadAs<PlayerCmdPayload>()
                if (cmd == null) {
                    err(envelope, "Invalid command")
                } else if (unsupportedPlayerCmd(cmd)) {
                    // A fallback UNKNOWN action (a value this TV does not know, decoded
                    // leniently from a newer phone) is rejected, never routed: a no-op
                    // must not be acknowledged as success.
                    err(envelope, "Unsupported command")
                } else {
                    NowPlayingHub.routeCommand(cmd)
                    ok(envelope)
                }
            }

            RemoteMessageType.SELECT_TRACK -> {
                val selection = payload.payloadAs<SelectTrackPayload>()
                if (selection == null || unsupportedSelectTrack(selection)) {
                    err(envelope, "Unsupported track")
                } else {
                    val applied = withContext(Dispatchers.Main) {
                        NowPlayingHub.selectTrack(selection)
                    }
                    if (applied) ok(envelope) else err(envelope, "Track unavailable")
                }
            }

            RemoteMessageType.SELECT_PLAYBACK_OPTION -> {
                val selection = payload.payloadAs<SelectPlaybackOptionPayload>()
                if (selection == null || unsupportedPlaybackOption(selection)) {
                    err(envelope, "Unsupported playback option")
                } else when (selection.type) {
                    SelectPlaybackOptionPayload.Type.SOURCE -> {
                        val play = NowPlayingHub.selectSource(selection.index!!)
                        if (play == null) err(envelope, "Source unavailable") else {
                            PendingCommandQueue.submit(RemoteMessageType.PLAY, encodePayload(play), context)
                            ok(envelope)
                        }
                    }
                    SelectPlaybackOptionPayload.Type.SUBTITLE -> {
                        val applied = withContext(Dispatchers.Main) {
                            NowPlayingHub.selectSubtitle(selection.index)
                        }
                        if (applied) ok(envelope) else err(envelope, "Subtitle unavailable")
                    }
                    SelectPlaybackOptionPayload.Type.UNKNOWN -> err(envelope, "Unsupported playback option")
                }
            }

            RemoteMessageType.GET_STATE -> {
                val state = NowPlayingHub.currentState()
                ok(envelope, state?.let { encodePayload(it) })
            }

            RemoteMessageType.UNPAIR -> {
                // The request socket is revoked by the server after its acknowledgement is sent.
                PairingManager.forgetPhone(envelope.deviceId, revokeSockets = false)
                ok(envelope)
            }

            RemoteMessageType.SYNC_EXTENSIONS -> {
                val sync = payload.payloadAs<com.lagradost.cloudstream3.remote.ExtensionSyncPayload>()
                if (sync == null) {
                    err(envelope, "Invalid payload")
                } else {
                    val reply = ExtensionSyncManager.apply(context, sync) { status ->
                        NowPlayingHub.broadcast(
                            com.lagradost.cloudstream3.remote.RemoteEvent(
                                kind = com.lagradost.cloudstream3.remote.RemoteEvent.Kind.PLUGIN_SYNC_STATUS,
                                pluginSync = status,
                            )
                        )
                    }
                    ok(envelope, encodePayload(reply))
                }
            }

            RemoteMessageType.EXT_FILE_START -> {
                val start = payload.payloadAs<ExtFileStartPayload>()
                if (start == null) {
                    err(envelope, "Invalid payload")
                } else {
                    val result = ExtensionSyncManager.extFileStart(context, envelope.deviceId, start)
                    result?.let { ok(envelope, encodePayload(it)) } ?: ok(envelope)
                }
            }

            RemoteMessageType.EXT_FILE_CHUNK -> {
                val chunk = payload.payloadAs<ExtFileChunkPayload>()
                if (chunk == null) {
                    err(envelope, "Invalid payload")
                } else {
                    val result = ExtensionSyncManager.extFileChunk(envelope.deviceId, chunk)
                    result?.let { ok(envelope, encodePayload(it)) } ?: ok(envelope)
                }
            }

            RemoteMessageType.EXT_FILE_END -> {
                val end = payload.payloadAs<ExtFileEndPayload>()
                if (end == null) {
                    err(envelope, "Invalid payload")
                } else {
                    val result = ExtensionSyncManager.extFileEnd(context, envelope.deviceId, end)
                    result?.let { ok(envelope, encodePayload(it)) } ?: ok(envelope)
                }
            }

            RemoteMessageType.SYNC_LIBRARY -> {
                val sync = payload.payloadAs<LibrarySyncPayload>()
                if (sync == null) {
                    err(envelope, "Invalid payload")
                } else {
                    val replyPayload = LibrarySyncManager.handleSyncLibrary(context, sync)
                    ok(envelope, encodePayload(replyPayload))
                }
            }

            else -> err(envelope, "unsupported")
        }
    }

    fun buildDeviceInfo(context: Context, paired: Boolean): DeviceInfo {
        val isTv = PairingManager.isTelevision(context)
        val capabilities = if (isTv) {
            setOf(
                DeviceInfo.CAP_EVENTS,
                DeviceInfo.CAP_EXT_SYNC,
                DeviceInfo.CAP_LIB_SYNC,
                DeviceInfo.CAP_PLAYER_CMD,
                DeviceInfo.CAP_INPUT_TEXT,
                DeviceInfo.CAP_INPUT_CONTEXT,
                DeviceInfo.CAP_TRACKS,
                DeviceInfo.CAP_PLAYBACK_CHOICES,
            )
        } else {
            emptySet()
        }
        return DeviceInfo(
            deviceId = PairingManager.myDeviceId(context),
            name = PairingManager.myDeviceName(),
            appVersion = BuildConfig.VERSION_NAME,
            protocol = LanRemoteProtocol.VERSION,
            isTv = isTv,
            paired = paired,
            capabilities = capabilities,
            pluginSetHash = runCatching { ExtensionSyncManager.pluginSetHash() }.getOrNull(),
            librarySetHash = runCatching { LibrarySyncManager.librarySetHash(context) }.getOrNull(),
        )
    }

    // ------------------------------------------------------------------

    private fun unsupportedSelectTrack(selection: SelectTrackPayload): Boolean = when (selection.type) {
        SelectTrackPayload.TrackType.VIDEO,
        SelectTrackPayload.TrackType.AUDIO -> selection.id.isNullOrBlank()
        SelectTrackPayload.TrackType.TEXT -> selection.id != null
        SelectTrackPayload.TrackType.UNKNOWN -> true
    }

    private fun unsupportedPlaybackOption(selection: SelectPlaybackOptionPayload): Boolean = when (selection.type) {
        SelectPlaybackOptionPayload.Type.SOURCE -> selection.index == null || selection.index < 0
        SelectPlaybackOptionPayload.Type.SUBTITLE -> selection.index != null && selection.index < 0
        SelectPlaybackOptionPayload.Type.UNKNOWN -> true
    }

    private fun dispatchKey(context: Context, keyCode: Int) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            val direction = when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
                KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
                else -> AudioManager.ADJUST_TOGGLE_MUTE
            }
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI,
            )
            return
        }

        val activity = CommonActivity.activity ?: return
        val now = android.os.SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun dispatchText(text: String) {
        val activity = CommonActivity.activity ?: return
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(text.toCharArray()) ?: return
        events.forEach(activity::dispatchKeyEvent)
    }

    /**
     * Replaces the whole text of the currently focused editable field (SearchView included),
     * Unicode-safe because the string is never sliced char-by-char, clears on empty input,
     * and leaves the cursor at the end. Returns false when no editable view is focused.
     */
    private fun applyInputText(text: String): Boolean {
        val activity = CommonActivity.activity ?: return false
        val view = activity.currentFocus ?: return false
        val edit = when (view) {
            is android.widget.EditText -> view
            is androidx.appcompat.widget.SearchView ->
                view.findViewById<android.widget.EditText>(androidx.appcompat.R.id.search_src_text)
            else -> null
        } ?: return false
        if (!edit.hasFocus()) return false
        edit.setText(text)
        edit.setSelection(edit.text.length)
        return true
    }

    private fun launchApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
            android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
    }

    /**
     * Pure gate for PLAYER_CMD (server-feasible test target): a null payload or the lenient
     * fallback [PlayerCmdPayload.Action.UNKNOWN] (a value this TV does not know, decoded from
     * a newer phone) is answered accepted=false and never routed — a no-op must not be
     * acknowledged as success.
     */
    internal fun unsupportedPlayerCmd(cmd: PlayerCmdPayload?): Boolean =
        cmd == null || cmd.action == PlayerCmdPayload.Action.UNKNOWN

    private fun ok(envelope: RemoteEnvelope, payload: JsonObject? = null) = RemoteReply(
        requestId = envelope.requestId,
        accepted = true,
        payload = payload,
    )

    private fun err(envelope: RemoteEnvelope, error: String) = RemoteReply(
        requestId = envelope.requestId,
        accepted = false,
        error = error,
    )
}
