package com.lagradost.cloudstream3.companion.phone

import com.lagradost.cloudstream3.companion.protocol.Envelope
import com.lagradost.cloudstream3.companion.protocol.Event
import com.lagradost.cloudstream3.companion.protocol.EventKind
import com.lagradost.cloudstream3.companion.protocol.InputContext
import com.lagradost.cloudstream3.companion.protocol.KeyRequest
import com.lagradost.cloudstream3.companion.protocol.MessageType
import com.lagradost.cloudstream3.companion.protocol.NavigationDirection
import com.lagradost.cloudstream3.companion.protocol.OpenPageRequest
import com.lagradost.cloudstream3.companion.protocol.PlayRequest
import com.lagradost.cloudstream3.companion.protocol.PlayerCommand
import com.lagradost.cloudstream3.companion.protocol.ResultPayload
import com.lagradost.cloudstream3.companion.protocol.InputTextRequest
import com.lagradost.cloudstream3.companion.protocol.ProtocolJson
import com.lagradost.cloudstream3.companion.protocol.SelectAudioRequest
import com.lagradost.cloudstream3.companion.protocol.SelectSourceRequest
import com.lagradost.cloudstream3.companion.protocol.SelectSubtitleRequest
import com.lagradost.cloudstream3.companion.protocol.SyncRecordPayload
import com.lagradost.cloudstream3.companion.protocol.SyncRequestPayload
import com.lagradost.cloudstream3.companion.protocol.TracksAvailable
import com.lagradost.cloudstream3.companion.sync.CompanionSyncRecord
import com.lagradost.cloudstream3.companion.sync.toPayload
import com.lagradost.cloudstream3.companion.sync.toSyncRecord
import com.lagradost.cloudstream3.companion.transport.CompanionEndpoint
import com.lagradost.cloudstream3.companion.transport.CompanionReconnectLoop
import com.lagradost.cloudstream3.companion.transport.CompanionConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class PhoneSessionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

data class PhoneNowPlaying(
    val state: com.lagradost.cloudstream3.companion.protocol.PlaybackState,
)

data class PhoneNavigationRequest(
    val lineageId: String,
    val direction: NavigationDirection,
)

fun interface PhoneSessionDialer {
    suspend fun dial(endpoint: CompanionEndpoint): PhoneWireConnection
}

interface PhoneWireConnection : Closeable {
    suspend fun readFrame(): ByteArray

    suspend fun writeFrame(payload: ByteArray)
}

/** Adapter for the authenticated TCP connection supplied by the transport layer. */
class CompanionConnectionWire(
    private val connection: CompanionConnection,
) : PhoneWireConnection {
    override suspend fun readFrame(): ByteArray = withContext(Dispatchers.IO) {
        connection.readFrame()
    }

    override suspend fun writeFrame(payload: ByteArray) = withContext(Dispatchers.IO) {
        connection.writeFrame(payload)
    }

    override fun close() = connection.close()
}

/**
 * Phone-side session facade. Authentication is deliberately outside this class:
 * [PhoneSessionDialer] must return only an authenticated connection. This class owns envelopes,
 * event routing, recovery,
 * and reconnect state after authentication succeeds.
 */
class PhoneCompanionSession(
    private val scope: CoroutineScope,
    private val dialer: PhoneSessionDialer,
    private val pipeline: LinkResolutionPipeline,
    private val clock: CompanionClock,
    private val onStateChanged: (PhoneSessionState) -> Unit = {},
    private val onNowPlaying: (PhoneNowPlaying) -> Unit = {},
    private val onInputContext: (InputContext) -> Unit = {},
    private val onNavigationRequested: (PhoneNavigationRequest) -> Unit = {},
    private val onTerminalFailure: (RecoveryTerminalFailure) -> Unit = {},
    private val onTracksAvailable: (TracksAvailable) -> Unit = {},
    private val onSyncRecord: (CompanionSyncRecord) -> Unit = {},
) : Closeable {
    private val stateMutex = Mutex()
    private val pendingResults = ConcurrentHashMap<String, CompletableDeferred<ResultPayload>>()
    private var endpoint: CompanionEndpoint? = null
    private var connection: PhoneWireConnection? = null
    private var reconnectLoop: CompanionReconnectLoop? = null
    private var backgroundJob: Job? = null
    private var state = PhoneSessionState.DISCONNECTED
    private var stopping = false
    private var activeInput: LinkResolutionInput? = null
    private var latestPositionMs: Long? = null
    @Volatile
    private var lastResolutionWasDrmOnly = false
    private var keepaliveJob: Job? = null

    private val recovery = PhoneRecoveryCoordinator(
        scope = scope,
        resolver = PlayRequestResolver { attempt ->
            val input = activeInput ?: return@PlayRequestResolver null
            val adjusted = input.copy(
                lineageId = attempt.lineageId,
                attempt = attempt.attempt,
                startPositionMs = attempt.startPositionMs ?: input.startPositionMs,
            )
            when (val outcome = pipeline.resolve(adjusted)) {
                is LinkResolutionOutcome.Success -> {
                    lastResolutionWasDrmOnly = false
                    outcome.request
                }
                is LinkResolutionOutcome.NoCandidates -> {
                    lastResolutionWasDrmOnly = outcome.droppedDrmLinks > 0 &&
                        input.links.isNotEmpty() &&
                        input.links.all { it is com.lagradost.cloudstream3.utils.DrmExtractorLink }
                    null
                }
                LinkResolutionOutcome.BudgetExpired -> {
                    lastResolutionWasDrmOnly = false
                    null
                }
            }
        },
        sender = PlayRequestSender { request -> sendPlay(request) },
        clock = clock,
        onTerminalFailure = { failure ->
            val mapped = if (
                failure.reason == RecoveryTerminalReason.RESOLUTION_FAILED &&
                lastResolutionWasDrmOnly
            ) {
                failure.copy(reason = RecoveryTerminalReason.DRM_ONLY)
            } else {
                failure
            }
            lastResolutionWasDrmOnly = false
            onTerminalFailure(mapped)
        },
    )

    val sessionState: PhoneSessionState
        get() = state

    val activeLineageId: String?
        get() = recovery.activeLineageId

    suspend fun connect(endpoint: CompanionEndpoint) {
        reconnectLoop?.stop()
        reconnectLoop = null
        stopConnection()
        this.endpoint = endpoint
        stopping = false
        setState(PhoneSessionState.CONNECTING)
        val loop = CompanionReconnectLoop(
            scope = scope,
            connect = { dialAndServe(endpoint) },
            onFailure = { if (!stopping) setState(PhoneSessionState.CONNECTING) },
        )
        reconnectLoop = loop
        loop.start()
    }

    suspend fun disconnect() {
        stopping = true
        backgroundJob?.cancel()
        backgroundJob = null
        stopKeepalive()
        reconnectLoop?.stop()
        reconnectLoop = null
        stopConnection()
        activeInput = null
        recovery.onDisconnected()
        setState(PhoneSessionState.DISCONNECTED)
    }

    /** Suspends reconnect attempts after a prolonged background period; foreground resumes them. */
    fun onAppBackground() {
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(BACKGROUND_RECONNECT_GRACE_MS)
            if (sessionState != PhoneSessionState.CONNECTED && !stopping) {
                reconnectLoop?.stop()
                reconnectLoop = null
                stopKeepalive()
                setState(PhoneSessionState.DISCONNECTED)
            }
        }
    }

    fun onAppForeground() {
        backgroundJob?.cancel()
        backgroundJob = null
        val target = endpoint
        if (target != null && !stopping && sessionState == PhoneSessionState.DISCONNECTED) {
            scope.launch { connect(target) }
        }
    }

    /** Starts a fresh user intent and cancels all resolution work from the previous lineage. */
    fun play(input: LinkResolutionInput): String {
        activeInput = input
        return recovery.start(input.startPositionMs)
    }

    suspend fun stopPlayback() {
        recovery.stop()
        sendCommand(MessageType.PLAYER_CMD, PlayerCommand(
            action = com.lagradost.cloudstream3.companion.protocol.PlayerAction.STOP,
        ))
    }

    suspend fun sendPlayerCommand(command: PlayerCommand): Boolean =
        sendCommand(MessageType.PLAYER_CMD, command)

    suspend fun sendKey(keyCode: Int): Boolean =
        sendCommand(MessageType.KEY, KeyRequest(keyCode))

    suspend fun sendInputText(text: String): Boolean =
        sendCommand(MessageType.INPUT_TEXT, InputTextRequest(text))

    suspend fun openPage(request: OpenPageRequest): Boolean =
        sendCommand(MessageType.OPEN_PAGE, request)

    suspend fun unpair(): Boolean = sendCommand(MessageType.UNPAIR, null)

    suspend fun sendPing(): Boolean = sendCommand(MessageType.PING, null)

    suspend fun subscribe(): Boolean = sendCommand(MessageType.SUBSCRIBE, null)

    suspend fun selectSource(index: Int, lineageId: String? = activeLineageId): Boolean =
        sendCommand(MessageType.SELECT_SOURCE, SelectSourceRequest(index, lineageId))

    suspend fun selectAudio(index: Int, lineageId: String? = activeLineageId): Boolean =
        sendCommand(MessageType.SELECT_AUDIO, SelectAudioRequest(index, lineageId))

    suspend fun selectSubtitle(index: Int?, lineageId: String? = activeLineageId): Boolean =
        sendCommand(MessageType.SELECT_SUBTITLE, SelectSubtitleRequest(index, lineageId))

    suspend fun requestSync(
        accountNamespace: String,
        field: com.lagradost.cloudstream3.companion.protocol.SyncField,
        mediaId: Int,
    ): Boolean = sendCommand(
        MessageType.SYNC_REQUEST,
        SyncRequestPayload(accountNamespace, field, mediaId),
    )

    suspend fun pushSync(record: CompanionSyncRecord): Boolean =
        sendCommand(MessageType.SYNC_PUSH, record.toPayload())

    private suspend fun sendPlay(request: PlayRequest): Boolean {
        if (!PhonePayloadValidator.isValid(request)) return false
        return sendCommand(MessageType.PLAY, request)
    }

    private suspend fun <T> sendCommand(type: MessageType, payload: T): Boolean {
        val wire = stateMutex.withLock { connection } ?: return false
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<ResultPayload>()
        pendingResults[id] = result
        try {
            val element = when (payload) {
                null -> null
                is KeyRequest -> ProtocolJson.encode(KeyRequest.serializer(), payload)
                is PlayerCommand -> ProtocolJson.encode(PlayerCommand.serializer(), payload)
                is InputTextRequest -> ProtocolJson.encode(InputTextRequest.serializer(), payload)
                is OpenPageRequest -> ProtocolJson.encode(OpenPageRequest.serializer(), payload)
                is PlayRequest -> ProtocolJson.encode(PlayRequest.serializer(), payload)
                is SelectSourceRequest ->
                    ProtocolJson.encode(SelectSourceRequest.serializer(), payload)
                is SelectAudioRequest ->
                    ProtocolJson.encode(SelectAudioRequest.serializer(), payload)
                is SelectSubtitleRequest ->
                    ProtocolJson.encode(SelectSubtitleRequest.serializer(), payload)
                is SyncRequestPayload ->
                    ProtocolJson.encode(SyncRequestPayload.serializer(), payload)
                is SyncRecordPayload ->
                    ProtocolJson.encode(SyncRecordPayload.serializer(), payload)
                else -> error("unsupported companion payload: ${payload::class}")
            }
            wire.writeFrame(
                ProtocolJson.encodeEnvelope(Envelope(id = id, type = type, payload = element)),
            )
            return withTimeout(COMMAND_TIMEOUT_MS) { result.await().ok }
        } catch (_: TimeoutCancellationException) {
            return false
        } catch (_: Throwable) {
            return false
        } finally {
            pendingResults.remove(id)
        }
    }

    private fun startKeepalive() {
        stopKeepalive()
        keepaliveJob = scope.launch {
            while (true) {
                delay(KEEPALIVE_PING_INTERVAL_MS)
                if (sessionState != PhoneSessionState.CONNECTED) break
                runCatching { sendPing() }
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    private suspend fun dialAndServe(endpoint: CompanionEndpoint) {
        val wire = dialer.dial(endpoint)
        connection = wire
        setState(PhoneSessionState.CONNECTED)
        startKeepalive()
        try {
            coroutineScope {
                val reader = launch(Dispatchers.IO) { readLoop(wire) }
                // The reader must already be active so SUBSCRIBE can receive its RESULT.
                sendCommand(MessageType.SUBSCRIBE, null)
                reader.join()
            }
        } finally {
            stopKeepalive()
            stateMutex.withLock {
                if (connection === wire) connection = null
            }
            wire.close()
            setState(if (stopping) PhoneSessionState.DISCONNECTED else PhoneSessionState.CONNECTING)
            if (!stopping) recovery.onDisconnected()
            throw IOException("companion connection closed")
        }
    }

    private suspend fun readLoop(wire: PhoneWireConnection) {
        while (true) {
            val envelope = ProtocolJson.decodeEnvelope(wire.readFrame())
            when (envelope.type) {
                MessageType.RESULT -> {
                    val payload = envelope.payload ?: continue
                    val result = ProtocolJson.decode(ResultPayload.serializer(), payload)
                    pendingResults.remove(envelope.id)?.complete(result)
                }
                MessageType.EVENT -> envelope.payload?.let { payload ->
                    handleEvent(ProtocolJson.decode(Event.serializer(), payload))
                }
                else -> Unit
            }
        }
    }

    private suspend fun handleEvent(event: Event) {
        when (event.kind) {
            EventKind.PLAYBACK_STATE -> event.playbackState?.let { playback ->
                latestPositionMs = playback.positionMs
                onNowPlaying(PhoneNowPlaying(playback))
                val active = recovery.activeLineageId
                if (
                    playback.state ==
                        com.lagradost.cloudstream3.companion.protocol.PlaybackStateKind.IDLE &&
                    active != null &&
                    playback.lineageId == active
                ) {
                    recovery.stop()
                }
            }
            EventKind.INPUT_CONTEXT -> event.inputContext?.let(onInputContext)
            EventKind.LINK_FAILED -> event.linkFailed?.let { failure ->
                recovery.onLinkFailed(failure, latestPositionMs)
            }
            EventKind.NAV_REQUESTED -> event.navRequested?.let { navigation ->
                if (navigation.lineageId == recovery.activeLineageId) {
                    onNavigationRequested(
                        PhoneNavigationRequest(navigation.lineageId, navigation.direction)
                    )
                }
            }
            EventKind.TRACKS_AVAILABLE -> event.tracksAvailable?.let(onTracksAvailable)
            EventKind.SYNC_RECORD -> event.syncRecord?.let { onSyncRecord(it.toSyncRecord()) }
        }
    }

    private suspend fun stopConnection() {
        stateMutex.withLock {
            connection?.close()
            connection = null
        }
    }

    private fun setState(newState: PhoneSessionState) {
        if (state == newState) return
        state = newState
        onStateChanged(newState)
    }

    override fun close() {
        scope.launch { disconnect() }
    }

    companion object {
        const val COMMAND_TIMEOUT_MS = 10_000L
        const val BACKGROUND_RECONNECT_GRACE_MS = 5 * 60_000L
        const val KEEPALIVE_PING_INTERVAL_MS = 2 * 60_000L
    }
}
