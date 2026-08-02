package com.lagradost.cloudstream3.remote

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.remote.sync.ExtensionSyncManager
import com.lagradost.cloudstream3.remote.sync.LibrarySyncManager
import com.lagradost.cloudstream3.remote.sync.SyncHooks
import com.lagradost.cloudstream3.remote.ui.CompanionNotificationManager
import com.lagradost.cloudstream3.remote.ui.CompanionSettingsActivity
import com.lagradost.cloudstream3.utils.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone-side singleton holding the persistent event channel to the active TV (plan §5.3):
 * connects -> SUBSCRIBE -> streams [RemoteEvent]s into [nowPlaying] / library deltas.
 * Reconnects with exponential-ish backoff (1 s on drop, 30 s on unreachable) while the app
 * is alive. All one-shot commands go through [send] so UI code never touches sockets.
 */
object CompanionSessionManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _nowPlaying = MutableStateFlow<NowPlayingPayload?>(null)
    val nowPlaying: StateFlow<NowPlayingPayload?> = _nowPlaying

    private val _tvOnline = MutableStateFlow(false)
    val tvOnline: StateFlow<Boolean> = _tvOnline

    private val started = AtomicBoolean(false)
    private var contextRef: Context? = null
    private var eventChannelJob: Job? = null
    private var pluginSyncJob: Job? = null
    private val libraryListener: (String) -> Unit = { key ->
        contextRef?.let { LibrarySyncManager.onLibraryChanged(it, key) }
    }
    private val pluginListener: (Unit) -> Unit = { scheduleExtensionSync() }
    private val repoListener: (Unit) -> Unit = { scheduleExtensionSync() }

    @Volatile
    var lastSyncResults: List<PluginSyncResult> = emptyList()
        private set

    /** Fired after [lastSyncResults] changes so Extensions UI can refresh its TV badges (plan §8.5). */
    val syncCompletedEvent = Event<Unit>()

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        contextRef = context.applicationContext
        SyncHooks.onPluginsChanged += pluginListener
        SyncHooks.onRepositoriesChanged += repoListener
        MainActivity.libraryChangedEvent += libraryListener
        scope.launch { connectLoop() }
    }

    fun stop() {
        if (!started.getAndSet(false)) return
        SyncHooks.onPluginsChanged -= pluginListener
        SyncHooks.onRepositoriesChanged -= repoListener
        MainActivity.libraryChangedEvent -= libraryListener
        pluginSyncJob?.cancel()
        pluginSyncJob = null
        eventChannelJob?.cancel()
        eventChannelJob = null
        publishNowPlaying(null)
    }

    /** One-shot signed command to the active TV. Throws when nothing is paired. */
    suspend fun send(type: RemoteMessageType, payload: Any? = null): RemoteReply =
        LanRemoteClient.sendActive(type, payload)

    suspend fun refreshState() {
        runCatching {
            val reply = LanRemoteClient.sendActive(RemoteMessageType.GET_STATE)
            publishNowPlaying(reply.payloadAs<NowPlayingPayload>())
        }
    }

    /** Sets the phone-visible now-playing state and mirrors it into the notification (plan §6.5). */
    private fun publishNowPlaying(payload: NowPlayingPayload?) {
        _nowPlaying.value = payload
        contextRef?.let { CompanionNotificationManager.update(it, payload) }
    }

    // ------------------------------------------------------------------
    // Extension sync (phone -> TV), debounced 5 s (plan §8.1)
    // ------------------------------------------------------------------

    private fun scheduleExtensionSync() {
        pluginSyncJob?.cancel()
        pluginSyncJob = scope.launch {
            delay(5_000)
            syncExtensions()
        }
    }

    suspend fun syncExtensions() {
        val context = contextRef ?: return
        if (!CompanionSettingsActivity.syncExtensionsEnabled()) return
        if (PairingManager.getActiveTv() == null) return
        val payload = ExtensionSyncManager.buildPayload(context)
        val reply = runCatching {
            LanRemoteClient.sendActive(RemoteMessageType.SYNC_EXTENSIONS, payload)
        }.getOrNull() ?: return
        if (!reply.accepted) return
        val replyPayload = reply.payloadAs<ExtensionSyncReply>() ?: return
        lastSyncResults = replyPayload.results
        syncCompletedEvent.invoke(Unit)

        // Repo unreachable from the TV -> push the local plugin bytes (plan §8.4).
        val failedDownloads = replyPayload.results.filter {
            it.status == PluginSyncResult.Status.FAILED && it.message == "download"
        }
        if (failedDownloads.isNotEmpty() && PairingManager.getActiveTv() != null) {
            val tv = PairingManager.getActiveTv() ?: return
            failedDownloads.forEach { result ->
                scope.launch {
                    runCatching { ExtensionSyncManager.pushPlugin(context, tv, result.internalName) }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Event channel
    // ------------------------------------------------------------------

    private suspend fun connectLoop() {
        while (started.get()) {
            val tv = PairingManager.getActiveTv()
            if (tv == null) {
                _tvOnline.value = false
                delay(5_000)
                continue
            }
            val wasOnline = connectAndSubscribe(tv)
            _tvOnline.value = wasOnline
            delay(if (wasOnline) 1_000 else 30_000)
        }
    }

    private suspend fun connectAndSubscribe(tv: PairedTv): Boolean {
        val context = contextRef ?: return false
        val socket = runCatching {
            Socket().apply {
                // Idle channel: the TV sends nothing while nothing is playing, so a plain
                // read would time out and cause a reconnect/full-sync churn every ~30 s.
                // We keep a window long enough to detect a dead peer and send an
                // authenticated keepalive PING on timeout instead of dropping (review R#2).
                soTimeout = 25_000
                connect(InetSocketAddress(tv.host, tv.port), 3_000)
            }
        }.getOrNull() ?: return false

        return try {
            val output = DataOutputStream(socket.getOutputStream())
            val requestId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            LanRemoteProtocol.write(
                output,
                RemoteEnvelope(
                    requestId = requestId,
                    deviceId = PairingManager.myDeviceId(context),
                    timestampMs = now,
                    auth = RemoteAuth.sign(tv.token, requestId, now),
                    type = RemoteMessageType.SUBSCRIBE,
                ),
            )
            val reply = LanRemoteProtocol.read<RemoteReply>(DataInputStream(socket.getInputStream()))
            if (!reply.accepted) {
                socket.close()
                return false
            }
            _tvOnline.value = true
            PairingManager.updateTvEndpoint(tv.deviceId, tv.host, tv.port)

            // HELLO + full sync once per (re)connect.
            eventChannelJob?.cancel()
            eventChannelJob = scope.launch { onConnected(context, tv) }

            val input = DataInputStream(socket.getInputStream())
            while (started.get()) {
                val event = try {
                    LanRemoteProtocol.read<RemoteEvent>(input)
                } catch (e: SocketTimeoutException) {
                    // Idle: ping on the event channel so the server-side 30 s read window
                    // never fires, then keep waiting. A failed write means the peer is gone.
                    val pinged = runCatching {
                        val now = System.currentTimeMillis()
                        val id = UUID.randomUUID().toString()
                        LanRemoteProtocol.write(
                            output,
                            RemoteEnvelope(
                                requestId = id,
                                deviceId = PairingManager.myDeviceId(context),
                                timestampMs = now,
                                auth = RemoteAuth.sign(tv.token, id, now),
                                type = RemoteMessageType.PING,
                            ),
                        )
                    }
                    if (pinged.isFailure) break
                    continue
                } catch (_: Exception) {
                    break
                }
                handleEvent(event)
            }
            runCatching { socket.close() }
            _tvOnline.value = false
            publishNowPlaying(null)
            true
        } catch (e: Exception) {
            runCatching { socket.close() }
            _tvOnline.value = false
            publishNowPlaying(null)
            false
        }
    }

    private fun handleEvent(event: RemoteEvent) {
        when (event.kind) {
            RemoteEvent.Kind.PLAYBACK_STATE -> publishNowPlaying(event.nowPlaying)
            RemoteEvent.Kind.PLAYER_GONE -> publishNowPlaying(null)
            RemoteEvent.Kind.LIBRARY_DELTA -> {
                event.libraryDelta?.let { delta ->
                    contextRef?.let { ctx ->
                        scope.launch { LibrarySyncManager.apply(ctx, delta) }
                    }
                }
            }
            RemoteEvent.Kind.PLUGIN_SYNC_STATUS -> Unit
            RemoteEvent.Kind.PAIRING_STARTED -> Unit
        }
    }

    private suspend fun onConnected(context: Context, tv: PairedTv) {
        runCatching {
            val reply = LanRemoteClient.send(tv, RemoteMessageType.HELLO)
            val info = reply.payloadAs<DeviceInfo>()
            // Cheap change detector (plan §8.1): sync extensions when the TV set differs.
            if (info?.pluginSetHash != null && info.pluginSetHash != ExtensionSyncManager.pluginSetHash()) {
                syncExtensions()
            }
            // Full library sync only when the two libraries actually differ; after the first
            // exchange they converge and reconnect no longer re-dumps everything.
            if (info?.librarySetHash != LibrarySyncManager.librarySetHash(context)) {
                runCatching { LibrarySyncManager.fullSyncPhone(context) }
            }
        }
        runCatching {
            val reply = LanRemoteClient.send(tv, RemoteMessageType.GET_STATE)
            publishNowPlaying(reply.payloadAs<NowPlayingPayload>())
        }
    }
}
