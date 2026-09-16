package com.lagradost.cloudstream3.companion.runtime

import android.content.Context
import android.content.res.Configuration
import android.webkit.CookieManager
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.companion.CompanionPreferences
import com.lagradost.cloudstream3.companion.crypto.AndroidKeyStoreImpl
import com.lagradost.cloudstream3.companion.crypto.CompanionRole
import com.lagradost.cloudstream3.companion.crypto.PairingWindow
import com.lagradost.cloudstream3.companion.crypto.RevocationRegistry
import com.lagradost.cloudstream3.companion.phone.CompanionClock
import com.lagradost.cloudstream3.companion.phone.LinkResolutionInput
import com.lagradost.cloudstream3.companion.phone.LinkResolutionPipeline
import com.lagradost.cloudstream3.companion.phone.OkHttpCandidateProbe
import com.lagradost.cloudstream3.companion.phone.PhoneCompanionSession
import com.lagradost.cloudstream3.companion.phone.PhoneSessionDialer
import com.lagradost.cloudstream3.companion.phone.PhoneSessionState
import com.lagradost.cloudstream3.companion.phone.CompanionPhoneTrackSelectionAdapter
import com.lagradost.cloudstream3.companion.protocol.Event
import com.lagradost.cloudstream3.companion.protocol.InputContext
import com.lagradost.cloudstream3.companion.protocol.PlayerAction
import com.lagradost.cloudstream3.companion.protocol.PlayerCommand
import com.lagradost.cloudstream3.companion.protocol.ProtocolJson
import com.lagradost.cloudstream3.companion.protocol.ResolvedSubtitle
import com.lagradost.cloudstream3.companion.protocol.SyncField
import com.lagradost.cloudstream3.companion.protocol.toSubtitleFile
import com.lagradost.cloudstream3.companion.sync.AndroidCompanionDataStoreHelperAdapter
import com.lagradost.cloudstream3.companion.sync.CompanionSyncApplyResult
import com.lagradost.cloudstream3.companion.sync.CompanionSyncEngine
import com.lagradost.cloudstream3.companion.sync.CompanionSyncField
import com.lagradost.cloudstream3.companion.sync.CompanionSyncRecord
import com.lagradost.cloudstream3.companion.sync.CompanionWatchState
import com.lagradost.cloudstream3.companion.sync.toPayload
import com.lagradost.cloudstream3.companion.tv.CompanionGeneratorFactory
import com.lagradost.cloudstream3.companion.tv.CompanionGeneratorPlaybackListener
import com.lagradost.cloudstream3.companion.tv.CompanionNowPlayingHub
import com.lagradost.cloudstream3.companion.tv.CompanionNowPlayingReporter
import com.lagradost.cloudstream3.companion.tv.CompanionPlaybackLauncher
import com.lagradost.cloudstream3.companion.tv.CompanionTvCommandSession
import com.lagradost.cloudstream3.companion.tv.CompanionTvInputHandler
import com.lagradost.cloudstream3.companion.tv.CompanionTvKeyHandler
import com.lagradost.cloudstream3.companion.tv.CompanionTvMainThreadDispatcher
import com.lagradost.cloudstream3.companion.tv.CompanionTvOpenPageHandler
import com.lagradost.cloudstream3.companion.tv.CompanionTvUnpairHandler
import com.lagradost.cloudstream3.companion.tv.CompanionTvTrackSelectionHandler
import com.lagradost.cloudstream3.companion.tv.NoOpCompanionTvTrackSelectionHandler
import com.lagradost.cloudstream3.companion.tv.ImmediateCompanionTvMainThreadDispatcher
import com.lagradost.cloudstream3.companion.tv.NoOpCompanionTvInputHandler
import com.lagradost.cloudstream3.companion.tv.NoOpCompanionTvKeyHandler
import com.lagradost.cloudstream3.companion.tv.PlaybackStartFailure
import com.lagradost.cloudstream3.companion.tv.StartupFailureStage
import com.lagradost.cloudstream3.companion.tv.TvPlaybackLauncher
import com.lagradost.cloudstream3.companion.tv.PlaybackStartResult
import com.lagradost.cloudstream3.companion.transport.CompanionConnection
import com.lagradost.cloudstream3.companion.transport.CompanionEndpoint
import com.lagradost.cloudstream3.companion.transport.CompanionNsdManager
import com.lagradost.cloudstream3.companion.transport.CompanionServiceRecord
import com.lagradost.cloudstream3.companion.transport.CompanionTcpClient
import com.lagradost.cloudstream3.companion.transport.CompanionTcpServer
import com.lagradost.cloudstream3.companion.ui.CompanionLaunchIdentity
import com.lagradost.cloudstream3.companion.ui.CompanionPlayerController
import com.lagradost.cloudstream3.companion.ui.CompanionUiBridge
import com.lagradost.cloudstream3.companion.tv.CompanionTvSessionSink
import com.lagradost.cloudstream3.companion.ui.CompanionUiBridge.Device
import com.lagradost.cloudstream3.companion.ui.CompanionUiBridge.InputContext as UiInputContext
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.network.buildDefaultClient
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private class TvSinkRegistry : CompanionTvSessionSink {
    private data class Entry(val sink: CompanionTvSessionSink)
    private val sinks = ConcurrentHashMap<String, Entry>()

    fun register(deviceId: String, sink: CompanionTvSessionSink): AutoCloseable {
        val entry = Entry(sink)
        sinks[deviceId] = entry
        return AutoCloseable { sinks.remove(deviceId, entry) }
    }

    override fun sendResult(deviceId: String, requestId: String, result: com.lagradost.cloudstream3.companion.protocol.ResultPayload) {
        sinks[deviceId]?.sink?.sendResult(deviceId, requestId, result)
    }

    override fun sendEvent(deviceId: String, event: Event) {
        sinks[deviceId]?.sink?.sendEvent(deviceId, event)
    }

    override fun showStatus(message: com.lagradost.cloudstream3.companion.tv.TvStatusMessage) = Unit
}

enum class CompanionRuntimeRole {
    PHONE,
    TV,
}

data class CompanionTvRuntimeAdapters(
    val playbackFactory: CompanionGeneratorFactory? = null,
    val keyHandler: CompanionTvKeyHandler = NoOpCompanionTvKeyHandler,
    val inputHandler: CompanionTvInputHandler = NoOpCompanionTvInputHandler,
    val openPageHandler: CompanionTvOpenPageHandler =
        com.lagradost.cloudstream3.companion.tv.NoOpCompanionTvOpenPageHandler,
    val mainThread: CompanionTvMainThreadDispatcher = ImmediateCompanionTvMainThreadDispatcher,
    val trackSelectionHandler: CompanionTvTrackSelectionHandler =
        NoOpCompanionTvTrackSelectionHandler,
)

/** Owns the one application-scoped companion composition for the current device. */
class CompanionRuntimeController(
    context: Context,
    private val role: CompanionRuntimeRole,
    private val tvAdapters: CompanionTvRuntimeAdapters = CompanionTvRuntimeAdapters(),
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("companion-runtime"),
    ),
) : Closeable, CompanionUiBridge.Actions {
    private val appContext = context.applicationContext
    private val keyStore by lazy { AndroidKeyStoreImpl(appContext) }
    private val identityAlias = if (role == CompanionRuntimeRole.TV) {
        TvCompanionAuthenticator.TV_IDENTITY_ALIAS
    } else {
        PHONE_IDENTITY_ALIAS
    }
    private val identity by lazy { keyStore.getOrCreateIdentity(identityAlias) }
    private val revocations by lazy { RevocationRegistry(keyStore) }
    private val syncEngine by lazy {
        CompanionSyncEngine(AndroidCompanionDataStoreHelperAdapter(appContext))
    }
    private val pairingWindow = PairingWindow()
    private val lifecycleMutex = Mutex()
    private val tvSessions = ConcurrentHashMap<String, TvSessionRecord>()
    private var tvServer: CompanionTcpServer? = null
    private var tvAuthenticator: TvCompanionAuthenticator? = null
    private var tvController: com.lagradost.cloudstream3.companion.tv.CompanionTvSessionController? = null
    private var tvLauncher: CompanionPlaybackLauncher? = null
    private val tvSinks = TvSinkRegistry()
    private var phoneSession: PhoneCompanionSession? = null
    private var phoneEndpoint: CompanionEndpoint? = null
    private var phonePeerAlias: String? = null
    private var phoneTrackSelection: CompanionPhoneTrackSelectionAdapter? = null
    private var discovered = emptyList<CompanionServiceRecord>()
    private var pairingPin: String? = null
    private var pairingExpiry: Long? = null

    private val nsd by lazy { CompanionNsdManager(appContext, ::onDiscovered) }

    private val nowPlaying = CompanionNowPlayingHub(
        eventSink = ::broadcastEvent,
    )

    init {
        CompanionUiBridge.install(this)
        if (role == CompanionRuntimeRole.TV) {
            if (CompanionPreferences.isEnabled(appContext)) {
                scope.launch { startTv() }
            }
        } else if (CompanionPreferences.isEnabled(appContext)) {
            nsd.startDiscovery()
            publishDevices()
        }
    }

    override fun setEnabled(enabled: Boolean) {
        CompanionPreferences.setEnabled(appContext, enabled)
        if (role != CompanionRuntimeRole.TV) {
            scope.launch {
                if (enabled) {
                    nsd.startDiscovery()
                } else {
                    nsd.stop()
                    phoneSession?.disconnect()
                    phoneSession = null
                    phoneTrackSelection = null
                    phonePeerAlias = null
                    phoneEndpoint = null
                    CompanionUiBridge.publishPlayback(null)
                    CompanionUiBridge.publishTracks(null)
                }
                publishDevices()
            }
            return
        }
        scope.launch { if (enabled) startTv() else stopTv() }
    }

    override fun approvePairing() {
        if (role == CompanionRuntimeRole.TV) tvAuthenticator?.approvePending()
    }

    override fun rejectPairing() {
        if (role == CompanionRuntimeRole.TV) tvAuthenticator?.rejectPending()
    }

    override fun startPairing() {
        if (role != CompanionRuntimeRole.TV || !CompanionPreferences.isEnabled(appContext)) return
        scope.launch {
            startTv()
            val pin = pairingWindow.open()
            pairingPin = pin
            pairingExpiry = System.currentTimeMillis() + PairingWindow.DEFAULT_DURATION_MS
            CompanionUiBridge.publishPairing(
                CompanionUiBridge.Pairing(pin = pin, expiresAtMs = pairingExpiry),
            )
        }
    }

    override fun stopPairing() {
        if (role != CompanionRuntimeRole.TV) return
        tvAuthenticator?.rejectPending()
        pairingWindow.close()
        pairingPin = null
        pairingExpiry = null
        CompanionUiBridge.publishPairing(null)
    }

    override fun pairTv(address: String?, pin: String?) {
        if (role != CompanionRuntimeRole.PHONE || pin.isNullOrBlank()) return
        val endpoint = address?.let { runCatching { CompanionEndpoint.parse(it) }.getOrNull() }
            ?: discovered.firstOrNull { !isPaired(it.fingerprint) }
                ?.let { CompanionEndpoint(it.host, it.port) }
            ?: return
        scope.launch { pairAndConnect(endpoint, pin) }
    }

    override fun connectTv(deviceId: String) {
        if (role != CompanionRuntimeRole.PHONE || keyStore.getPeer(deviceId) == null) return
        val endpointText = CompanionPreferences.endpoint(appContext, deviceId)
            ?: discovered.firstOrNull { it.fingerprint == deviceId }
                ?.let { "${formatHost(it.host)}:${it.port}" }
            ?: return
        val endpoint = runCatching { CompanionEndpoint.parse(endpointText) }.getOrNull() ?: return
        scope.launch { connectPhone(endpoint, deviceId) }
    }

    override fun revoke(deviceId: String) {
        scope.launch {
            if (role == CompanionRuntimeRole.TV) {
                // Tear down controller state and the live event channel before deleting the key.
                tvController?.onPhoneUnpaired(deviceId)
                tvSessions.remove(deviceId)?.close()
                tvAuthenticator?.revoke(deviceId)
                revocations.revoke(deviceId)
                publishDevices()
            } else if (phonePeerAlias == deviceId) {
                phoneSession?.disconnect()
                keyStore.removePeer(deviceId)
                phoneSession = null
                phoneTrackSelection = null
                phonePeerAlias = null
                phoneEndpoint = null
                CompanionPreferences.removeEndpoint(appContext, deviceId)
                publishDevices()
            }
        }
    }

    override fun playOnTv(
        title: String,
        episodeLabel: String?,
        posterUrl: String?,
        mediaId: Int?,
        links: List<ExtractorLink>,
        subtitles: List<SubtitleData>,
    ): Boolean {
        if (role != CompanionRuntimeRole.PHONE || phoneSession?.sessionState != PhoneSessionState.CONNECTED) {
            return false
        }
        val session = phoneSession ?: return false
        scope.launch {
            val subtitleFiles = subtitles.map { subtitle ->
                newSubtitleFile(subtitle.originalName, subtitle.getFixedUrl()) {
                    headers = subtitle.headers
                }
            }
            val accountNamespace = DataStoreHelper.currentAccount
            if (mediaId != null) {
                syncEngine.read(
                    accountNamespace,
                    CompanionSyncField.VIDEO_POS_DUR,
                    mediaId,
                )?.let { session.pushSync(it) }
                syncEngine.read(
                    accountNamespace,
                    CompanionSyncField.VIDEO_WATCH_STATE,
                    mediaId,
                )?.let { session.pushSync(it) }
                session.requestSync(accountNamespace, SyncField.VIDEO_POS_DUR, mediaId)
            }
            session.play(
                LinkResolutionInput(
                    links = links,
                    subtitles = subtitleFiles,
                    title = title,
                    episodeLabel = episodeLabel,
                    posterUrl = posterUrl,
                    mediaId = mediaId,
                    lineageId = UUID.randomUUID().toString(),
                ),
            )
        }
        return true
    }

    override fun sendPlayerCommand(action: String, positionMs: Long?) {
        if (role != CompanionRuntimeRole.PHONE) return
        val playerAction = runCatching { PlayerAction.valueOf(action) }.getOrNull() ?: return
        scope.launch {
            phoneSession?.sendPlayerCommand(PlayerCommand(playerAction, positionMs = positionMs))
        }
    }

    override fun sendKey(keyCode: Int) {
        if (role == CompanionRuntimeRole.PHONE) scope.launch { phoneSession?.sendKey(keyCode) }
    }

    override fun sendInputText(text: String) {
        if (role == CompanionRuntimeRole.PHONE) scope.launch { phoneSession?.sendInputText(text) }
    }

    override fun close() = runBlocking { closeAndJoin() }

    /** Completes transport teardown before returning, allowing singleton recreation in tests. */
    suspend fun closeAndJoin() {
        if (role == CompanionRuntimeRole.TV) {
            stopTv()
            CompanionPlayerController.installReporter(null)
        } else {
            nsd.stop()
            phoneSession?.disconnect()
            phoneSession = null
            phoneTrackSelection = null
            phonePeerAlias = null
            phoneEndpoint = null
        }
        CompanionUiBridge.publishTracks(null)
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    private suspend fun startTv() = lifecycleMutex.withLock {
        if (role != CompanionRuntimeRole.TV || tvServer?.isRunning == true) return@withLock
        val sharedController = tvController ?: run {
            val launcher = tvAdapters.playbackFactory?.let {
                CompanionPlaybackLauncher(it, nowPlaying)
            } ?: UnavailableTvPlaybackLauncher
            tvLauncher = launcher as? CompanionPlaybackLauncher
            com.lagradost.cloudstream3.companion.tv.CompanionTvSessionController(
                launcher = launcher,
                sink = tvSinks,
                playbackControls = object : com.lagradost.cloudstream3.companion.tv.TvPlaybackControls {
                    override fun hasPlayer(): Boolean = CompanionPlayerController.hasActivePlayer()
                    override fun dispatch(action: String, positionMs: Long?): Boolean =
                        CompanionPlayerController.dispatch(action, positionMs)
                },
            ).also { tvController = it }
        }
        CompanionPlayerController.installReporter(PlayerReporter())
        CompanionPlayerController.installTvNavigator { direction ->
            tvController?.requestTvNavigation(direction) == true
        }
        val authenticator = TvCompanionAuthenticator(keyStore, {
            CompanionPreferences.deviceName(appContext)
        }, pairingWindow,
            onPendingApproval = { deviceId, deviceName, expiresAtMs ->
                CompanionUiBridge.publishPairing(
                    CompanionUiBridge.Pairing(
                        pin = pairingPin,
                        expiresAtMs = expiresAtMs,
                        pendingDeviceId = deviceId,
                        pendingDeviceName = deviceName,
                    ),
                )
            },
            onApprovalCleared = {
                CompanionUiBridge.publishPairing(
                    pairingPin?.let { pin ->
                        CompanionUiBridge.Pairing(pin = pin, expiresAtMs = pairingExpiry)
                    },
                )
                publishDevices()
            },
        )
        val server = CompanionTcpServer(
            scope = scope,
            authenticator = authenticator,
            handler = { connection -> handleTvConnection(connection, authenticator) },
        )
        val port = server.start()
        tvAuthenticator = authenticator
        tvServer = server
        nsd.register(
            serviceName = CompanionPreferences.deviceName(appContext),
            port = port,
            deviceName = CompanionPreferences.deviceName(appContext),
            fingerprint = fingerprint(identity.public.encoded),
        )
        publishDevices()
    }

    private suspend fun stopTv() = lifecycleMutex.withLock {
        stopPairing()
        tvController?.onLocalPlaybackChanged()
        tvLauncher?.stop()
        tvSessions.values.toList().forEach { it.close() }
        tvSessions.clear()
        tvServer?.stop()
        tvServer = null
        tvAuthenticator = null
        CompanionPlayerController.installReporter(null)
        CompanionPlayerController.installTvNavigator(null)
        tvController = null
        tvLauncher = null
        nsd.stop()
        publishDevices()
    }

    private suspend fun handleTvConnection(
        connection: CompanionConnection,
        authenticator: TvCompanionAuthenticator,
    ) {
        val state = authenticator.take(connection.deviceId) ?: return
        val wire = TvRuntimeWire(connection, state.session.recordLayer)
        val subscription = com.lagradost.cloudstream3.companion.tv.CompanionTvEventSubscription()
        val wireSink = com.lagradost.cloudstream3.companion.tv.CompanionTvWireSessionSink(wire, subscription)
        val sharedController = tvController ?: return
        lateinit var commandSession: CompanionTvCommandSession
        val playbackListener = object : CompanionGeneratorPlaybackListener {
            override fun onPlaybackState(
                state: com.lagradost.cloudstream3.companion.protocol.PlaybackStateKind,
                positionMs: Long,
                durationMs: Long,
            ) {
                nowPlaying.onPlaybackState(state, positionMs, durationMs)
                publishSyncPosition()
                scope.launch {
                    commandSession.controller.onPlaybackPosition(positionMs)
                }
            }

            override fun onLinkFailure(linkIndex: Int, failure: PlaybackStartFailure) {
                scope.launch {
                    commandSession.controller.onPlaybackFailure(linkIndex, failure)
                }
            }

            override fun onPlaybackEnded() {
                nowPlaying.onPlaybackState(
                    com.lagradost.cloudstream3.companion.protocol.PlaybackStateKind.ENDED,
                    0L,
                    0L,
                )
                publishSyncWatched()
                scope.launch { commandSession.controller.onPlaybackEnded() }
            }

            override fun onLocalPlaybackChanged() {
                scope.launch { commandSession.controller.onLocalPlaybackChanged() }
            }
        }
        commandSession = CompanionTvCommandSession(
            connection = wire,
            launcher = tvLauncher ?: UnavailableTvPlaybackLauncher,
            sharedController = sharedController,
            keyHandler = tvAdapters.keyHandler,
            inputHandler = tvAdapters.inputHandler,
            openPageHandler = tvAdapters.openPageHandler,
            unpairHandler = object : CompanionTvUnpairHandler {
                override fun unpair(deviceId: String): Boolean = keyStore.getPeer(deviceId) != null
            },
            onUnpairCleanup = { commandSession.controller.onPhoneUnpaired(connection.deviceId) },
            onUnpairComplete = {
                authenticator.revoke(connection.deviceId)
                revocations.revoke(connection.deviceId)
            },
            mainThread = tvAdapters.mainThread,
            trackSelectionHandler = tvAdapters.trackSelectionHandler,
            syncEngine = syncEngine,
            currentAccountNamespace = { DataStoreHelper.currentAccount },
            sinkOverride = wireSink,
            eventSubscriptionOverride = subscription,
        )
        val sinkRegistration = tvSinks.register(connection.deviceId, wireSink)
        val registration = revocations.register(connection.deviceId) { connection.close() }
        val record = TvSessionRecord(connection, commandSession, registration)
        tvSessions[connection.deviceId]?.close()
        tvSessions[connection.deviceId] = record
        CompanionUiBridge.setRemotePhoneName(keyStore.getPeer(connection.deviceId)?.deviceName)
        publishDevices()
        try {
            while (true) {
                val envelope = ProtocolJson.decodeEnvelope(wire.readFrame())
                commandSession.route(envelope)
            }
        } catch (_: Throwable) {
            commandSession.disconnect()
        } finally {
            registration.close()
            sinkRegistration.close()
            tvSessions.remove(connection.deviceId, record)
            if (tvSessions.isEmpty()) CompanionUiBridge.setRemotePhoneName(null)
            publishDevices()
        }
    }

    private suspend fun pairAndConnect(endpoint: CompanionEndpoint, pin: String) {
        val authenticator = PhonePairingAuthenticator(
            keyStore = keyStore,
            deviceName = CompanionPreferences.deviceName(appContext),
            pin = pin,
        )
        val client = CompanionTcpClient(
            scope = scope,
            authenticator = authenticator,
            handler = { awaitCancellation() },
        )
        runCatching {
            client.connect(endpoint)
            client.disconnect()
            val alias = authenticator.peerAlias ?: return@runCatching
            CompanionPreferences.setEndpoint(appContext, alias, formatEndpoint(endpoint))
            phonePeerAlias = alias
            connectPhone(endpoint, alias)
        }
    }

    private fun connectPhone(endpoint: CompanionEndpoint, peerAlias: String) {
        phoneSession?.close()
        val probe = OkHttpCandidateProbe(buildDefaultClient(appContext))
        val pipeline = LinkResolutionPipeline(
            probe = probe,
            clock = CompanionClock(System::currentTimeMillis),
            headerProvider = object : com.lagradost.cloudstream3.companion.phone.CompanionHeaderProvider {
                override fun webViewUserAgent(): String? = WebViewResolver.getWebViewUserAgent()
                override fun cookiesFor(url: String): String? = CookieManager.getInstance().getCookie(url)
            },
        )
        lateinit var trackSelection: CompanionPhoneTrackSelectionAdapter
        val session = PhoneCompanionSession(
            scope = scope,
            dialer = PhoneSessionDialer { dial(endpoint, peerAlias) },
            pipeline = pipeline,
            clock = CompanionClock(System::currentTimeMillis),
            onStateChanged = { state -> onPhoneState(state) },
            onNowPlaying = { playing ->
                val state = playing.state
                CompanionUiBridge.publishPlayback(
                    CompanionUiBridge.Playback(
                        title = state.title,
                        episodeLabel = state.episodeLabel,
                        posterUrl = state.posterUrl,
                        positionMs = state.positionMs,
                        durationMs = state.durationMs,
                        playing = state.state == com.lagradost.cloudstream3.companion.protocol.PlaybackStateKind.PLAYING,
                    ),
                )
            },
            onInputContext = { input ->
                val current = CompanionUiBridge.playback.value
                CompanionUiBridge.publishPlayback(
                    (current ?: CompanionUiBridge.Playback(""))
                        .copy(inputContext = if (input.context == com.lagradost.cloudstream3.companion.protocol.InputContextKind.SEARCH_FIELD) {
                            UiInputContext.SEARCH_FIELD
                        } else UiInputContext.IDLE),
                )
            },
            onTracksAvailable = { catalog -> trackSelection.accept(catalog) },
            onSyncRecord = { record ->
                syncEngine.apply(DataStoreHelper.currentAccount, record)
            },
        )
        trackSelection = CompanionPhoneTrackSelectionAdapter(
            session = session,
            onCatalogChanged = CompanionUiBridge::publishTracks,
        )
        phoneSession = session
        phoneTrackSelection = trackSelection
        phoneEndpoint = endpoint
        phonePeerAlias = peerAlias
        scope.launch { session.connect(endpoint) }
        publishDevices()
    }

    private suspend fun dial(endpoint: CompanionEndpoint, peerAlias: String): CompanionSecureWire {
        val authenticator = PhoneSessionAuthenticator(keyStore, peerAlias)
        val client = CompanionTcpClient(
            scope = scope,
            authenticator = authenticator,
            handler = { awaitCancellation() },
        )
        val connection = client.connect(endpoint)
        val established = authenticator.established ?: error("session authentication did not establish")
        return CompanionSecureWire(connection, established.recordLayer)
    }

    private fun onDiscovered(records: List<CompanionServiceRecord>) {
        discovered = records
        publishDevices()
    }

    private fun onPhoneState(state: PhoneSessionState) {
        publishDevices()
        if (state == PhoneSessionState.DISCONNECTED) {
            CompanionUiBridge.publishPlayback(null)
            phoneTrackSelection?.clear()
        }
    }

    /** Forwards app-background transitions to the phone session (phone role only). */
    fun onAppBackgrounded() {
        if (role == CompanionRuntimeRole.PHONE) phoneSession?.onAppBackground()
    }

    /** Forwards app-foreground transitions to the phone session (phone role only). */
    fun onAppForegrounded() {
        if (role == CompanionRuntimeRole.PHONE) phoneSession?.onAppForeground()
    }

    private fun publishDevices() {
        val devices = if (role == CompanionRuntimeRole.TV) {
            keyStore.listPeers().map { peer ->
                Device(peer.alias, peer.deviceName, paired = true, connected = tvSessions.containsKey(peer.alias))
            }
        } else {
            val pairedDevices = keyStore.listPeers().map { peer ->
                Device(
                    id = peer.alias,
                    name = peer.deviceName,
                    address = CompanionPreferences.endpoint(appContext, peer.alias),
                    paired = true,
                    connected = peer.alias == phonePeerAlias &&
                        phoneSession?.sessionState == PhoneSessionState.CONNECTED,
                )
            }
            val discoveredDevices = discovered.map { record ->
                Device(
                    id = record.fingerprint,
                    name = record.deviceName,
                    address = "${formatHost(record.host)}:${record.port}",
                    paired = isPaired(record.fingerprint),
                    connected = record.fingerprint == phonePeerAlias &&
                    phoneSession?.sessionState == PhoneSessionState.CONNECTED,
                )
            }
            (pairedDevices + discoveredDevices).distinctBy { it.id }
        }
        CompanionUiBridge.publishDevices(devices)
    }

    private fun isPaired(alias: String): Boolean = keyStore.getPeer(alias) != null

    private fun formatEndpoint(endpoint: CompanionEndpoint): String =
        if (endpoint.host.contains(':')) {
            "[${endpoint.host}]:${endpoint.port}"
        } else {
            "${endpoint.host}:${endpoint.port}"
        }

    private fun broadcastEvent(event: Event) {
        tvSessions.values.toList().forEach { session ->
            if (session.command.eventSubscription.enabled) session.command.sendEvent(event)
        }
    }

    private fun publishSyncPosition() {
        val snapshot = nowPlaying.snapshot() ?: return
        val mediaId = snapshot.metadata.mediaId ?: return
        val durationMs = snapshot.durationMs
        val positionMs = snapshot.positionMs
        if (durationMs < com.lagradost.cloudstream3.companion.sync.COMPANION_SYNC_MIN_DURATION_MS ||
            positionMs !in 0L..durationMs
        ) {
            return
        }
        val record = CompanionSyncRecord(
            accountNamespace = DataStoreHelper.currentAccount,
            field = CompanionSyncField.VIDEO_POS_DUR,
            mediaId = mediaId,
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAtMs = System.currentTimeMillis(),
        )
        if (syncEngine.apply(DataStoreHelper.currentAccount, record) !is CompanionSyncApplyResult.Applied) {
            return
        }
        broadcastEvent(
            Event(
                kind = com.lagradost.cloudstream3.companion.protocol.EventKind.SYNC_RECORD,
                syncRecord = record.toPayload(),
            ),
        )
    }

    private fun publishSyncWatched() {
        val mediaId = nowPlaying.snapshot()?.metadata?.mediaId ?: return
        val record = CompanionSyncRecord(
            accountNamespace = DataStoreHelper.currentAccount,
            field = CompanionSyncField.VIDEO_WATCH_STATE,
            mediaId = mediaId,
            watchState = CompanionWatchState.WATCHED,
            updatedAtMs = System.currentTimeMillis(),
        )
        if (syncEngine.apply(DataStoreHelper.currentAccount, record) !is CompanionSyncApplyResult.Applied) {
            return
        }
        broadcastEvent(
            Event(
                kind = com.lagradost.cloudstream3.companion.protocol.EventKind.SYNC_RECORD,
                syncRecord = record.toPayload(),
            ),
        )
    }

    private fun formatHost(host: String): String = if (host.contains(':')) "[$host]" else host

    private inner class PlayerReporter : CompanionPlayerController.Reporter,
        CompanionNowPlayingReporter {
        private var activeOwner: com.lagradost.cloudstream3.ui.player.GeneratorPlayer? = null
        private var activeLaunch: CompanionLaunchIdentity? = null

        override fun register(
            owner: com.lagradost.cloudstream3.ui.player.GeneratorPlayer,
            launch: CompanionLaunchIdentity?,
        ) {
            activeOwner = owner
            activeLaunch = launch
            if (launch == null && role == CompanionRuntimeRole.TV) {
                scope.launch { tvController?.onLocalPlaybackChanged() }
            }
        }

        override fun unregister(owner: com.lagradost.cloudstream3.ui.player.GeneratorPlayer) {
            if (activeOwner !== owner) return
            val launch = activeLaunch
            activeOwner = null
            activeLaunch = null
            if (launch != null) {
                nowPlaying.unregister(launch.lineageId)
                scope.launch { tvController?.onLocalPlaybackChanged() }
            }
        }

        override fun onPlaybackState(
            owner: com.lagradost.cloudstream3.ui.player.GeneratorPlayer,
            positionMs: Long,
            durationMs: Long,
            playing: Boolean,
        ) {
            val launch = activeLaunch
            if (activeOwner !== owner || launch == null) return
            nowPlaying.onPlaybackState(
                if (playing) com.lagradost.cloudstream3.companion.protocol.PlaybackStateKind.PLAYING
                else com.lagradost.cloudstream3.companion.protocol.PlaybackStateKind.PAUSED,
                positionMs,
                durationMs,
            )
            scope.launch {
                if (tvController?.snapshot()?.lineageId != launch.lineageId) return@launch
                tvController?.onPlaybackPosition(positionMs)
                publishSyncPosition()
            }
        }

        override fun onPlaybackError(
            owner: com.lagradost.cloudstream3.ui.player.GeneratorPlayer,
            exception: Throwable,
        ) {
            val launch = activeLaunch
            if (activeOwner !== owner || launch == null) return
            val http = exception as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
            val status = http?.responseCode
            scope.launch {
                if (tvController?.snapshot()?.lineageId != launch.lineageId) return@launch
                val snapshot = nowPlaying.snapshot()
                val stage = if (status == 401 || status == 403) {
                    StartupFailureStage.AUTH
                } else if (snapshot == null || snapshot.durationMs <= 0L) {
                    StartupFailureStage.MANIFEST
                } else {
                    StartupFailureStage.SEGMENT
                }
                tvController?.onRuntimePlaybackFailure(
                    PlaybackStartFailure(stage = stage, httpStatus = status),
                )
            }
        }

        override fun register(lineageId: String, metadata: com.lagradost.cloudstream3.companion.tv.CompanionPlaybackMetadata) =
            nowPlaying.register(lineageId, metadata)

        override fun unregister(lineageId: String) = nowPlaying.unregister(lineageId)
    }

    private class TvSessionRecord(
        private val connection: CompanionConnection,
        val command: CompanionTvCommandSession,
        private val registration: AutoCloseable,
    ) {
        suspend fun close() {
            registration.close()
            command.disconnect()
            connection.close()
        }
    }

    private class TvRuntimeWire(
        private val connection: CompanionConnection,
        recordLayer: com.lagradost.cloudstream3.companion.crypto.AeadRecordLayer,
    ) : com.lagradost.cloudstream3.companion.tv.CompanionTvWireConnection {
        private val channel = com.lagradost.cloudstream3.companion.crypto.SecureChannelState()
            .also { it.establish(recordLayer) }
        override val deviceId: String get() = connection.deviceId
        override fun send(envelope: com.lagradost.cloudstream3.companion.protocol.Envelope) {
            connection.writeFrame(channel.encryptApplicationRecord(ProtocolJson.encodeEnvelope(envelope)))
        }
        fun readFrame(): ByteArray = channel.decryptApplicationRecord(connection.readFrame())
    }

    private object UnavailableTvPlaybackLauncher : TvPlaybackLauncher {
        override suspend fun startCandidate(
            request: com.lagradost.cloudstream3.companion.protocol.PlayRequest,
            candidate: com.lagradost.cloudstream3.companion.protocol.ResolvedLink,
            startPositionMs: Long?,
            deadlineMs: Long,
        ): PlaybackStartResult = PlaybackStartResult.Failed(
            PlaybackStartFailure(StartupFailureStage.UNKNOWN),
        )

        override fun stop() = Unit
    }
}

object CompanionRuntime {
    @Volatile
    private var controller: CompanionRuntimeController? = null

    fun install(
        context: Context,
        role: CompanionRuntimeRole = detectRole(context),
        tvAdapters: CompanionTvRuntimeAdapters = CompanionTvRuntimeAdapters(),
    ): CompanionRuntimeController = synchronized(this) {
        if (controller != null) return@synchronized controller!!
        val effectiveAdapters = if (
            role == CompanionRuntimeRole.TV &&
            tvAdapters.mainThread === ImmediateCompanionTvMainThreadDispatcher
        ) {
            tvAdapters.copy(
                mainThread = com.lagradost.cloudstream3.companion.tv.CoroutineCompanionTvMainThreadDispatcher(
                    Dispatchers.Main.immediate,
                ),
            )
        } else {
            tvAdapters
        }
        CompanionRuntimeController(context, role, effectiveAdapters).also { controller = it }
    }

    fun current(): CompanionRuntimeController? = controller

    /** Stops and forgets the application-scoped runtime so a later install creates a fresh one. */
    fun clear() {
        val previous = synchronized(this) {
            controller.also { controller = null }
        }
        previous?.close()
    }

    private fun detectRole(context: Context): CompanionRuntimeRole =
        if ((context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        ) CompanionRuntimeRole.TV else CompanionRuntimeRole.PHONE
}
