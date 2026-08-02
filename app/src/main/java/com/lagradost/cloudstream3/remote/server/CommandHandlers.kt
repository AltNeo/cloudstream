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
import com.lagradost.cloudstream3.remote.TextPayload
import com.lagradost.cloudstream3.remote.encodePayload
import com.lagradost.cloudstream3.remote.payloadAs
import com.lagradost.cloudstream3.remote.sync.ExtensionSyncManager
import com.lagradost.cloudstream3.remote.sync.LibrarySyncManager
import com.lagradost.cloudstream3.ui.player.PlaybackCoordinator
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
                } else {
                    NowPlayingHub.routeCommand(cmd)
                    ok(envelope)
                }
            }

            RemoteMessageType.GET_STATE -> {
                val state = NowPlayingHub.currentState()
                ok(envelope, state?.let { encodePayload(it) })
            }

            RemoteMessageType.UNPAIR -> {
                PairingManager.forgetPhone(envelope.deviceId)
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
                    val result = ExtensionSyncManager.extFileStart(context, start)
                    result?.let { ok(envelope, encodePayload(it)) } ?: ok(envelope)
                }
            }

            RemoteMessageType.EXT_FILE_CHUNK -> {
                val chunk = payload.payloadAs<ExtFileChunkPayload>()
                if (chunk == null) {
                    err(envelope, "Invalid payload")
                } else {
                    val result = ExtensionSyncManager.extFileChunk(chunk)
                    result?.let { ok(envelope, encodePayload(it)) } ?: ok(envelope)
                }
            }

            RemoteMessageType.EXT_FILE_END -> {
                val end = payload.payloadAs<ExtFileEndPayload>()
                if (end == null) {
                    err(envelope, "Invalid payload")
                } else {
                    val result = ExtensionSyncManager.extFileEnd(context, end)
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

    private fun launchApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
            android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
    }

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
