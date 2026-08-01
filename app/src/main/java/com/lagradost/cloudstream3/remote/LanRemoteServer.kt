package com.lagradost.cloudstream3.remote

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.ui.player.OfflinePlaybackHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

object LanRemoteServer {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

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

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            try {
                val socket = ServerSocket(LanRemoteProtocol.PORT).apply {
                    reuseAddress = true
                }
                serverSocket = socket
                mainHandler.post { registerService(appContext) }
                while (isActive) {
                    val client = socket.accept()
                    runCatching { handleClient(appContext, client) }
                }
            } catch (_: SocketException) {
                started.set(false)
            } catch (_: Throwable) {
                started.set(false)
            }
        }
    }

    fun stop() {
        if (!started.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        val listener = registrationListener
        if (listener != null) {
            runCatching { nsdManager?.unregisterService(listener) }
        }
        registrationListener = null
        nsdManager = null
    }

    private fun handleClient(context: Context, client: java.net.Socket) {
        client.use { socket ->
            socket.soTimeout = 5_000
            val response = runCatching {
                val request = LanRemoteProtocol.read<LanRemoteRequest>(
                    DataInputStream(socket.getInputStream())
                )
                process(context, request)
            }.getOrElse { error ->
                LanRemoteResponse(
                    requestId = "unknown",
                    accepted = false,
                    message = error.message ?: "Invalid request",
                )
            }
            runCatching {
                LanRemoteProtocol.write(DataOutputStream(socket.getOutputStream()), response)
            }
        }
    }

    private fun process(context: Context, request: LanRemoteRequest): LanRemoteResponse {
        if (request.version != LanRemoteProtocol.VERSION) {
            return request.rejected("Unsupported protocol version")
        }

        return when (request.command) {
            LanRemoteCommand.PING -> request.accepted(deviceName())
            LanRemoteCommand.LAUNCH -> {
                mainHandler.post { launchApp(context) }
                request.accepted()
            }

            LanRemoteCommand.KEY -> {
                val keyCode = request.keyCode
                if (keyCode == null || keyCode !in allowedKeyCodes) {
                    request.rejected("Unsupported key")
                } else {
                    mainHandler.post { dispatchKey(context, keyCode) }
                    request.accepted()
                }
            }

            LanRemoteCommand.TEXT -> {
                val text = request.text?.takeIf { it.isNotBlank() }?.take(256)
                if (text == null) {
                    request.rejected("Text is empty")
                } else {
                    mainHandler.post { dispatchText(text) }
                    request.accepted()
                }
            }

            LanRemoteCommand.PLAY -> {
                val play = request.play
                if (play == null || play.links.isEmpty()) {
                    request.rejected("No playable links")
                } else {
                    mainHandler.post { play(context, play) }
                    request.accepted()
                }
            }
        }
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

    private fun play(context: Context, payload: LanRemotePlayPayload, retries: Int = 0) {
        val activity = CommonActivity.activity
        if (activity == null) {
            if (retries >= 10) return
            launchApp(context)
            mainHandler.postDelayed({ play(context, payload, retries + 1) }, 1_500)
            return
        }
        val intent = Intent().apply {
            putExtra(CloudStreamPackage.LINKS_EXTRA, payload.links.toTypedArray())
            putExtra(CloudStreamPackage.SUBTITLE_EXTRA, payload.subtitles.toTypedArray())
            payload.title?.let { putExtra(CloudStreamPackage.TITLE_EXTRA, it) }
            payload.mediaId?.let { putExtra(CloudStreamPackage.ID_EXTRA, it) }
            payload.positionMs?.let { putExtra(CloudStreamPackage.POSITION_EXTRA, it) }
            payload.durationMs?.let { putExtra(CloudStreamPackage.DURATION_EXTRA, it) }
        }
        OfflinePlaybackHelper.playIntent(activity, intent)
    }

    private fun launchApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
    }

    private fun registerService(context: Context) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = manager
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        manager.registerService(
            NsdServiceInfo().apply {
                serviceName = "CloudStream-${deviceName()}"
                serviceType = LanRemoteProtocol.SERVICE_TYPE
                port = LanRemoteProtocol.PORT
            },
            NsdManager.PROTOCOL_DNS_SD,
            listener,
        )
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    private fun LanRemoteRequest.accepted(message: String? = null) = LanRemoteResponse(
        requestId = requestId,
        accepted = true,
        message = message,
    )

    private fun LanRemoteRequest.rejected(message: String) = LanRemoteResponse(
        requestId = requestId,
        accepted = false,
        message = message,
    )
}
