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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
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

    // ------------------------------------------------------------------
    // Unified observable session state (plan F6.3, checkpoint 1)
    // ------------------------------------------------------------------

    /**
     * Single source of truth for the phone⇄TV session. The per-surface flows below
     * ([nowPlaying], [tvOnline], [tvCapabilities], [inputContext]) are derived projections of
     * it, so any UI can collect [state] once and react to every surface coherently while
     * existing collectors keep working unchanged. Not-yet-implemented surfaces (up-next,
     * clipboard, sources/tracks, queue) will extend [RemoteState] when their checkpoints land.
     */
    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state

    val nowPlaying: StateFlow<NowPlayingPayload?> =
        _state.map { it.nowPlaying }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    val tvOnline: StateFlow<Boolean> =
        _state.map { it.tvOnline }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** Capabilities the active TV advertised in its HELLO DeviceInfo (plan §5.4). */
    val tvCapabilities: StateFlow<Set<String>> =
        _state.map { it.tvCapabilities }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Latest INPUT_CONTEXT (search focus / text echo) pushed by the TV, null when idle. */
    val inputContext: StateFlow<InputContextPayload?> =
        _state.map { it.inputContext }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    val tracks: StateFlow<TracksPayload?> =
        _state.map { it.tracks }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Wall-clock time the last non-null [NowPlayingPayload] was received. The phone uses it to
     * interpolate the position locally between the TV's throttled (10 s) PLAYBACK_STATE events
     * so the seekbar feels live (plan F3 / checkpoint 2) without extra network traffic.
     */
    @Volatile
    private var lastPlaybackSampleAtMs = 0L

    /**
     * Monotonic id of the current event-channel session. Bumped on every connect attempt,
     * disconnect, and [stop]; INPUT_CONTEXT events, HELLO capabilities and the send/echo
     * guard are all tied to it so state from a previous TV/connection can never leak
     * forward (finding 2).
     */
    @Volatile
    private var connectionEpoch = 0L
    @Volatile
    private var connectionTarget: PairedTv? = null

    private val started = AtomicBoolean(false)
    private var contextRef: Context? = null
    private var eventChannelJob: Job? = null
    private var connectLoopJob: Job? = null
    private var connectionGeneration = 0L
    @Volatile
    private var activeEventSocket: Socket? = null
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
        val generation = ++connectionGeneration
        connectLoopJob = scope.launch { connectLoop(generation) }
    }

    fun stop() {
        if (!started.getAndSet(false)) return
        SyncHooks.onPluginsChanged -= pluginListener
        SyncHooks.onRepositoriesChanged -= repoListener
        MainActivity.libraryChangedEvent -= libraryListener
        pluginSyncJob?.cancel()
        pluginSyncJob = null
        connectionGeneration += 1
        connectLoopJob?.cancel()
        connectLoopJob = null
        eventChannelJob?.cancel()
        eventChannelJob = null
        runCatching { activeEventSocket?.close() }
        activeEventSocket = null
        publishNowPlaying(null)
        connectionEpoch += 1
        connectionTarget = null
        _state.update {
            it.copy(tvOnline = false, inputContext = null, tracks = null, tvCapabilities = emptySet())
        }
    }

    /** Starts a fresh session: stale capability/input state from a previous TV or connection must not leak into it. */
    private fun beginConnection(tv: PairedTv): Long {
        // Cancel old connection work (onConnected HELLO/sync/GET_STATE) immediately so a
        // superseded session can never publish into or race the new one (review).
        eventChannelJob?.cancel()
        eventChannelJob = null
        _state.update {
            it.copy(
                activeTv = tv,
                tvOnline = false,
                tvCapabilities = emptySet(),
                inputContext = null,
                tracks = null,
            )
        }
        connectionEpoch += 1
        connectionTarget = tv
        return connectionEpoch
    }

    /** Immediately invalidates the event session when the user selects a different TV. */
    internal fun onActiveTvChanged() {
        connectionEpoch += 1
        connectionTarget = null
        eventChannelJob?.cancel()
        eventChannelJob = null
        runCatching { activeEventSocket?.close() }
        activeEventSocket = null
        publishNowPlaying(null)
        _state.update {
            it.copy(
                activeTv = PairingManager.getActiveTv(),
                tvOnline = false,
                tvCapabilities = emptySet(),
                inputContext = null,
                tracks = null,
            )
        }
    }

    /** Tears down session state, but only when [epoch] is still the current session. */
    private fun endConnection(epoch: Long) {
        if (epoch != connectionEpoch) return
        eventChannelJob?.cancel()
        eventChannelJob = null
        connectionTarget = null
        _state.update {
            it.copy(tvOnline = false, tvCapabilities = emptySet(), inputContext = null, tracks = null)
        }
    }

    /** One-shot signed command to the active TV. Throws when nothing is paired. */
    suspend fun send(type: RemoteMessageType, payload: Any? = null): RemoteReply =
        LanRemoteClient.sendActive(type, payload)

    /**
     * Whole-string input: sends [text] as an INPUT_TEXT replacement to the TV, but only when
     * the TV advertised [DeviceInfo.CAP_INPUT_TEXT]. Skips sending when the TV already echoed
     * this exact string back via INPUT_CONTEXT, which breaks the send/echo feedback loop. The
     * whole string must fit the documented input bound (code units AND serialized frame).
     *
     * The send context is snapshotted once (session epoch, active TV, capability, echo) and
     * rechecked immediately before the frame goes on the wire: if the session rolled over
     * (reconnect/switch/[stop]) or the active TV changed since the snapshot, the send is
     * aborted - it can never be sent to a different TV, and its send/echo suppression can
     * never be decided by a previous session's stale echo (finding 2 / review).
     */
    suspend fun sendInputText(text: String): Boolean {
        val epoch = connectionEpoch
        val activeTv = PairingManager.getActiveTv() ?: return false
        val target = connectionTarget ?: return false
        val snapshot = _state.value
        if (epoch != connectionEpoch) return false
        if (!snapshot.tvOnline || !sameRemoteTarget(activeTv, target)) return false
        if (DeviceInfo.CAP_INPUT_TEXT !in snapshot.tvCapabilities) return false
        if (!shouldSendInputText(text, snapshot.inputContext?.currentText)) {
            // Already echoed in this session -> treat as applied (feedback-loop guard);
            // oversized -> refuse. A stale echo from a previous session cannot reach here
            // because the epoch was rechecked above.
            return text == snapshot.inputContext?.currentText
        }
        val reply = runCatching {
            // Final recheck immediately before the wire: abort if the session or the active
            // TV changed since the snapshot (a reconnect/switch/stop raced this send).
            if (epoch != connectionEpoch) return@runCatching null
            if (connectionTarget !== target) return@runCatching null
            if (!sameRemoteTarget(PairingManager.getActiveTv(), target)) return@runCatching null
            LanRemoteClient.send(target, RemoteMessageType.INPUT_TEXT, InputTextPayload(text))
        }.getOrNull() ?: return false
        return reply.accepted && epoch == connectionEpoch &&
            connectionTarget === target && sameRemoteTarget(PairingManager.getActiveTv(), target)
    }

    /** Queues a whole-string send on the session scope so fragment dismissal cannot cancel it. */
    fun enqueueInputText(text: String): Job = scope.launch { sendInputText(text) }

    /**
     * Live phone-side playback position: the last PLAYING sample advanced monotonically by
     * wall-clock time at the reported speed (see [interpolatedPositionMs]). Null when nothing
     * is playing. Purely local — it never touches the network, it only bridges the TV's
     * existing 10 s state throttle so the seekbar feels live (checkpoint 2).
     */
    fun livePositionMs(nowMs: Long = System.currentTimeMillis()): Long? {
        val payload = _state.value.nowPlaying ?: return null
        return interpolatedPositionMs(payload, lastPlaybackSampleAtMs, nowMs)
    }

    suspend fun refreshState() {
        runCatching {
            val reply = LanRemoteClient.sendActive(RemoteMessageType.GET_STATE)
            publishNowPlaying(reply.payloadAs<NowPlayingPayload>())
        }
    }

    /** Sets the phone-visible now-playing state and mirrors it into the notification (plan §6.5). */
    private fun publishNowPlaying(payload: NowPlayingPayload?) {
        if (payload != null) lastPlaybackSampleAtMs = System.currentTimeMillis()
        _state.update { it.copy(nowPlaying = payload) }
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

    enum class ExtensionSyncStatus { SUCCESS, SKIPPED, FAILED }

    suspend fun syncExtensions(): ExtensionSyncStatus {
        val context = contextRef ?: return ExtensionSyncStatus.SKIPPED
        val tv = PairingManager.getActiveTv() ?: return ExtensionSyncStatus.SKIPPED
        return syncExtensionsTo(context, tv, expectedEpoch = null)
    }

    private suspend fun syncExtensionsTo(
        context: Context,
        tv: PairedTv,
        expectedEpoch: Long?,
    ): ExtensionSyncStatus {
        if (!CompanionSettingsActivity.syncExtensionsEnabled()) return ExtensionSyncStatus.SKIPPED
        fun targetIsCurrent(): Boolean = sameRemoteTarget(PairingManager.getActiveTv(), tv) &&
            (expectedEpoch == null ||
                (expectedEpoch == connectionEpoch && connectionTarget === tv))
        if (!targetIsCurrent()) return ExtensionSyncStatus.SKIPPED
        _state.update { it.copy(lastSyncStatus = SyncStatus.SYNCING) }
        val payload = ExtensionSyncManager.buildPayload(context)
        val reply = runCatching {
            LanRemoteClient.send(tv, RemoteMessageType.SYNC_EXTENSIONS, payload)
        }.getOrNull()
        if (!targetIsCurrent()) return ExtensionSyncStatus.SKIPPED
        if (reply == null || !reply.accepted) {
            _state.update { it.copy(lastSyncStatus = SyncStatus.ERROR) }
            return ExtensionSyncStatus.FAILED
        }
        val replyPayload = reply.payloadAs<ExtensionSyncReply>() ?: run {
            _state.update { it.copy(lastSyncStatus = SyncStatus.ERROR) }
            return ExtensionSyncStatus.FAILED
        }
        _state.update {
            it.copy(lastSyncStatus = SyncStatus.OK, lastSyncTime = System.currentTimeMillis())
        }
        lastSyncResults = replyPayload.results
        syncCompletedEvent.invoke(Unit)

        // Repo unreachable from the TV -> push the local plugin bytes (plan §8.4).
        val failedDownloads = replyPayload.results.filter {
            it.status == PluginSyncResult.Status.FAILED && it.message == "download"
        }
        if (failedDownloads.isNotEmpty() && targetIsCurrent()) {
            failedDownloads.forEach { result ->
                scope.launch {
                    if (targetIsCurrent()) {
                        runCatching {
                            ExtensionSyncManager.pushPlugin(
                                context,
                                tv,
                                result.internalName,
                                stillCurrent = ::targetIsCurrent,
                            )
                        }
                    }
                }
            }
        }
        return ExtensionSyncStatus.SUCCESS
    }

    // ------------------------------------------------------------------
    // Event channel
    // ------------------------------------------------------------------

    private suspend fun connectLoop(generation: Long) {
        while (started.get() && generation == connectionGeneration && kotlinx.coroutines.currentCoroutineContext().isActive) {
            val tv = PairingManager.getActiveTv()
            if (tv == null) {
                _state.update { it.copy(activeTv = null, tvOnline = false) }
                delay(5_000)
                continue
            }
            val wasOnline = connectAndSubscribe(tv)
            _state.update { it.copy(activeTv = PairingManager.getActiveTv()) }
            delay(if (wasOnline) 1_000 else 30_000)
        }
    }

    private suspend fun connectAndSubscribe(tv: PairedTv): Boolean {
        val context = contextRef ?: return false
        val transportKey = RemoteAuth.decodeBase64(tv.sessionKey)
            ?.takeIf { it.size == 32 } ?: return false
        val epoch = beginConnection(tv)
        val socket = runCatching {
            Socket().apply {
                // Idle channel: the TV sends nothing while nothing is playing, so a plain
                // read would time out and cause a reconnect/full-sync churn every ~30 s.
                // We keep a window long enough to detect a dead peer and send an
                // authenticated keepalive PING on timeout instead of dropping (review R#2).
                soTimeout = 25_000
                connect(InetSocketAddress(tv.host, tv.port), 3_000)
            }
        }.getOrNull() ?: run {
            endConnection(epoch)
            return false
        }
        activeEventSocket = socket

        return try {
            val output = DataOutputStream(socket.getOutputStream())
            val requestId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val unsigned = RemoteEnvelope(
                requestId = requestId,
                deviceId = PairingManager.myDeviceId(context),
                timestampMs = now,
                type = RemoteMessageType.SUBSCRIBE,
                payload = encodePayload(
                    SubscribePayload(
                        capabilities = setOf(
                            DeviceInfo.CAP_INPUT_TEXT,
                            DeviceInfo.CAP_INPUT_CONTEXT,
                            DeviceInfo.CAP_TRACKS,
                            DeviceInfo.CAP_PLAYBACK_CHOICES,
                        ),
                    )
                ),
            )
            val envelope = unsigned.copy(auth = RemoteAuth.sign(tv.token, unsigned))
            LanRemoteProtocol.writeEncrypted(
                output,
                transportKey,
                envelope.deviceId,
                LanRemoteProtocol.json.parseToJsonElement(LanRemoteProtocol.json.encodeToString(envelope)),
            )
            val reply = LanRemoteProtocol.json.decodeFromJsonElement<RemoteReply>(
                LanRemoteProtocol.readDecrypted(DataInputStream(socket.getInputStream()), transportKey).second
            )
            if (!reply.accepted) {
                socket.close()
                return false
            }
            if (epoch != connectionEpoch || connectionTarget !== tv ||
                !sameRemoteTarget(PairingManager.getActiveTv(), tv)
            ) {
                socket.close()
                endConnection(epoch)
                return false
            }
            _state.update { it.copy(tvOnline = true) }
            PairingManager.updateTvEndpoint(tv.deviceId, tv.host, tv.port)

            // HELLO + full sync once per (re)connect.
            eventChannelJob?.cancel()
            eventChannelJob = scope.launch { onConnected(context, tv, epoch) }

            val input = DataInputStream(socket.getInputStream())
            while (started.get()) {
                val event = try {
                    LanRemoteProtocol.json.decodeFromJsonElement<RemoteEvent>(
                        LanRemoteProtocol.readDecrypted(input, transportKey).second
                    )
                } catch (e: SocketTimeoutException) {
                    if (!sameRemoteTarget(PairingManager.getActiveTv(), tv)) break
                    // Idle: ping on the event channel so the server-side 30 s read window
                    // never fires, then keep waiting. A failed write means the peer is gone.
                    val pinged = runCatching {
                        val now = System.currentTimeMillis()
                        val id = UUID.randomUUID().toString()
                        val unsignedPing = RemoteEnvelope(
                            requestId = id,
                            deviceId = PairingManager.myDeviceId(context),
                            timestampMs = now,
                            type = RemoteMessageType.PING,
                        )
                        val ping = unsignedPing.copy(auth = RemoteAuth.sign(tv.token, unsignedPing))
                        LanRemoteProtocol.writeEncrypted(
                            output,
                            transportKey,
                            ping.deviceId,
                            LanRemoteProtocol.json.parseToJsonElement(
                                LanRemoteProtocol.json.encodeToString(ping)
                            ),
                        )
                    }
                    if (pinged.isFailure) break
                    continue
                } catch (_: Exception) {
                    break
                }
                if (!sameRemoteTarget(PairingManager.getActiveTv(), tv)) break
                handleEvent(event, epoch, tv)
            }
            runCatching { socket.close() }
            if (activeEventSocket === socket) activeEventSocket = null
            if (epoch == connectionEpoch) {
                _state.update { it.copy(tvOnline = false) }
                publishNowPlaying(null)
            }
            endConnection(epoch)
            true
        } catch (e: Exception) {
            runCatching { socket.close() }
            if (activeEventSocket === socket) activeEventSocket = null
            if (epoch == connectionEpoch) {
                _state.update { it.copy(tvOnline = false) }
                publishNowPlaying(null)
            }
            endConnection(epoch)
            false
        }
    }

    private fun handleEvent(event: RemoteEvent, epoch: Long, tv: PairedTv) {
        // Every event is bound to the session that produced it: a frame drained from a stale
        // socket after a reconnect/switch must never mutate the new session's state (review).
        if (epoch != connectionEpoch || connectionTarget !== tv ||
            !sameRemoteTarget(PairingManager.getActiveTv(), tv)
        ) return
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
            RemoteEvent.Kind.INPUT_CONTEXT -> {
                // The TV only streams INPUT_CONTEXT to phones that opted in on SUBSCRIBE, so
                // the event is trusted regardless of HELLO timing (no more discard-before-HELLO
                // race); it must still belong to the current session (checked above), and an
                // UNKNOWN surface is never stored (see effectiveInputContext).
                _state.update { it.copy(inputContext = effectiveInputContext(event.inputContext)) }
            }
            RemoteEvent.Kind.TRACKS_AVAILABLE -> {
                _state.update { it.copy(tracks = event.tracks) }
            }
            // Unknown kinds (newer TV) are ignored so the event loop keeps running.
            RemoteEvent.Kind.UNKNOWN -> Unit
        }
    }

    private suspend fun onConnected(context: Context, tv: PairedTv, epoch: Long) {
        runCatching {
            val reply = LanRemoteClient.send(tv, RemoteMessageType.HELLO)
            if (epoch != connectionEpoch) return@runCatching
            val info = reply.payloadAs<DeviceInfo>()
            // Cache what the TV supports so input is gated on the wire (plan §5.4 / C3), but
            // only for the current session: a superseded HELLO from a previous connection must
            // not overwrite the new session's capabilities.
            if (epoch == connectionEpoch) {
                _state.update { it.copy(tvCapabilities = info?.capabilities ?: emptySet()) }
            }
            // Cheap change detector (plan §8.1): sync extensions when the TV set differs.
            if (info?.pluginSetHash != null && info.pluginSetHash != ExtensionSyncManager.pluginSetHash()) {
                syncExtensionsTo(context, tv, epoch)
            }
            // Full library sync only when the two libraries actually differ; after the first
            // exchange they converge and reconnect no longer re-dumps everything.
            if (info?.librarySetHash != LibrarySyncManager.librarySetHash(context)) {
                if (epoch == connectionEpoch && connectionTarget === tv) {
                    runCatching {
                        LibrarySyncManager.fullSyncPhone(context, tv) {
                            epoch == connectionEpoch && connectionTarget === tv &&
                                sameRemoteTarget(PairingManager.getActiveTv(), tv)
                        }
                    }
                }
            }
        }
        runCatching {
            val reply = LanRemoteClient.send(tv, RemoteMessageType.GET_STATE)
            // Publish only when this connection is still the current session: a stale GET_STATE
            // reply (from a connection superseded while this coroutine was mid-flight) must not
            // overwrite the new session's now-playing (review).
            if (epoch == connectionEpoch) {
                publishNowPlaying(reply.payloadAs<NowPlayingPayload>())
            }
        }
    }
}

/**
 * Unified observable snapshot of the phone⇄TV companion session (plan F6.3, checkpoint 1).
 * [CompanionSessionManager.state] is the single source of truth; the individual per-surface
 * flows are derived projections of it. Surfaces that land in later checkpoints (up-next,
 * clipboard, sources/tracks, queue) will extend this type.
 */
data class RemoteState(
    val activeTv: PairedTv? = null,
    val tvOnline: Boolean = false,
    val nowPlaying: NowPlayingPayload? = null,
    val inputContext: InputContextPayload? = null,
    val tracks: TracksPayload? = null,
    val tvCapabilities: Set<String> = emptySet(),
    val lastSyncTime: Long? = null,
    val lastSyncStatus: SyncStatus = SyncStatus.IDLE,
) {
    /** True when the paired TV is online and advertised whole-string INPUT_TEXT support. */
    val canSendInputText: Boolean
        get() = tvOnline && DeviceInfo.CAP_INPUT_TEXT in tvCapabilities
}

/** Lifecycle of the last extension/library sync attempt (plan F6.3). */
enum class SyncStatus { IDLE, SYNCING, OK, ERROR }

/**
 * Input-context policy (finding 4): an UNKNOWN surface (the lenient decode fallback for a
 * context this phone does not know) is never stored as the active input context - the phone
 * cannot interpret it, so it must not show an input UI nor feed the send/echo guard.
 */
internal fun effectiveInputContext(payload: InputContextPayload?): InputContextPayload? =
    if (payload?.context == InputContextPayload.Context.UNKNOWN) null else payload

private fun sameRemoteTarget(left: PairedTv?, right: PairedTv): Boolean =
    left?.deviceId == right.deviceId &&
        left.token == right.token &&
        left.host == right.host &&
        left.port == right.port
