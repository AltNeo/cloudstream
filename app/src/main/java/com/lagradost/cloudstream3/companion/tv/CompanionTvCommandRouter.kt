package com.lagradost.cloudstream3.companion.tv

import com.lagradost.cloudstream3.companion.protocol.Envelope
import com.lagradost.cloudstream3.companion.protocol.COMPANION_PROTOCOL_VERSION
import com.lagradost.cloudstream3.companion.protocol.ErrorCode
import com.lagradost.cloudstream3.companion.protocol.EventKind
import com.lagradost.cloudstream3.companion.protocol.InputTextRequest
import com.lagradost.cloudstream3.companion.protocol.KeyRequest
import com.lagradost.cloudstream3.companion.protocol.MessageType
import com.lagradost.cloudstream3.companion.protocol.OpenPageRequest
import com.lagradost.cloudstream3.companion.protocol.ProtocolJson
import com.lagradost.cloudstream3.companion.protocol.ResultPayload
import com.lagradost.cloudstream3.companion.protocol.PlayerCommand
import com.lagradost.cloudstream3.companion.protocol.PlayRequest
import com.lagradost.cloudstream3.companion.protocol.Event
import com.lagradost.cloudstream3.companion.protocol.SelectAudioRequest
import com.lagradost.cloudstream3.companion.protocol.SelectSourceRequest
import com.lagradost.cloudstream3.companion.protocol.SelectSubtitleRequest
import com.lagradost.cloudstream3.companion.protocol.SyncRecordPayload
import com.lagradost.cloudstream3.companion.protocol.SyncRequestPayload
import com.lagradost.cloudstream3.companion.sync.CompanionSyncApplyResult
import com.lagradost.cloudstream3.companion.sync.CompanionSyncEngine
import com.lagradost.cloudstream3.companion.sync.CompanionSyncField
import com.lagradost.cloudstream3.companion.sync.toPayload
import com.lagradost.cloudstream3.companion.sync.toSyncRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

interface CompanionTvWireConnection {
    val deviceId: String

    fun send(envelope: Envelope)
}

class CompanionTvEventSubscription {
    @Volatile
    var enabled: Boolean = false
}

class CompanionTvWireSessionSink(
    private val connection: CompanionTvWireConnection,
    val subscription: CompanionTvEventSubscription = CompanionTvEventSubscription(),
) : CompanionTvSessionSink {
    override fun sendResult(deviceId: String, requestId: String, result: ResultPayload) {
        if (deviceId != connection.deviceId) return
        connection.send(
            Envelope(
                id = requestId,
                type = MessageType.RESULT,
                payload = ProtocolJson.encode(ResultPayload.serializer(), result),
            ),
        )
    }

    override fun sendEvent(deviceId: String, event: Event) {
        if (deviceId != connection.deviceId) return
        if (!subscription.enabled) return
        connection.send(
            Envelope(
                id = UUID.randomUUID().toString(),
                type = MessageType.EVENT,
                payload = ProtocolJson.encode(Event.serializer(), event),
            ),
        )
    }

    override fun showStatus(message: TvStatusMessage) = Unit
}

/** Wires one authenticated connection to a TV controller with a matching event subscription. */
class CompanionTvCommandSession(
    private val connection: CompanionTvWireConnection,
    launcher: TvPlaybackLauncher,
    sharedController: CompanionTvSessionController? = null,
    sinkOverride: CompanionTvSessionSink? = null,
    eventSubscriptionOverride: CompanionTvEventSubscription? = null,
    keyHandler: CompanionTvKeyHandler = NoOpCompanionTvKeyHandler,
    inputHandler: CompanionTvInputHandler = NoOpCompanionTvInputHandler,
    openPageHandler: CompanionTvOpenPageHandler = NoOpCompanionTvOpenPageHandler,
    unpairHandler: CompanionTvUnpairHandler = NoOpCompanionTvUnpairHandler,
    onUnpairCleanup: suspend () -> Unit = {},
    onUnpairComplete: suspend () -> Unit = {},
    mainThread: CompanionTvMainThreadDispatcher = CoroutineCompanionTvMainThreadDispatcher(),
    val eventSubscription: CompanionTvEventSubscription =
        eventSubscriptionOverride ?: CompanionTvEventSubscription(),
    trackSelectionHandler: CompanionTvTrackSelectionHandler =
        NoOpCompanionTvTrackSelectionHandler,
    syncEngine: CompanionSyncEngine? = null,
    currentAccountNamespace: () -> String = { "0" },
) {
    private val sink = sinkOverride ?: CompanionTvWireSessionSink(connection, eventSubscription)
    val trackCatalogPublisher: CompanionTvTrackCatalogPublisher =
        CompanionTvEventTrackCatalogPublisher { event ->
            sink.sendEvent(connection.deviceId, event)
        }
    val controller: CompanionTvSessionController = sharedController
        ?: CompanionTvSessionController(launcher = launcher, sink = sink)
    private val router = CompanionTvCommandRouter(
        connection = connection,
        controller = controller,
        keyHandler = keyHandler,
        inputHandler = inputHandler,
        trackSelectionHandler = trackSelectionHandler,
        openPageHandler = openPageHandler,
        unpairHandler = unpairHandler,
        onUnpairCleanup = onUnpairCleanup,
        onUnpairComplete = onUnpairComplete,
        mainThread = mainThread,
        eventSubscription = eventSubscription,
        syncEngine = syncEngine,
        currentAccountNamespace = currentAccountNamespace,
    )

    suspend fun route(envelope: Envelope) = router.route(envelope)

    suspend fun disconnect() = router.disconnect()

    fun sendEvent(event: Event) = sink.sendEvent(connection.deviceId, event)
}

interface CompanionTvKeyHandler {
    fun onKey(keyCode: Int): Boolean
}

interface CompanionTvInputHandler {
    fun onInputText(text: String): Boolean
}

interface CompanionTvOpenPageHandler {
    fun openPage(apiName: String, url: String): Boolean
}

interface CompanionTvUnpairHandler {
    fun unpair(deviceId: String): Boolean
}

object NoOpCompanionTvKeyHandler : CompanionTvKeyHandler {
    override fun onKey(keyCode: Int): Boolean = false
}

object NoOpCompanionTvInputHandler : CompanionTvInputHandler {
    override fun onInputText(text: String): Boolean = false
}

object NoOpCompanionTvOpenPageHandler : CompanionTvOpenPageHandler {
    override fun openPage(apiName: String, url: String): Boolean = false
}

object NoOpCompanionTvUnpairHandler : CompanionTvUnpairHandler {
    override fun unpair(deviceId: String): Boolean = false
}

interface CompanionTvMainThreadDispatcher {
    suspend fun <T> dispatch(block: suspend () -> T): T
}

class CoroutineCompanionTvMainThreadDispatcher(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : CompanionTvMainThreadDispatcher {
    override suspend fun <T> dispatch(block: suspend () -> T): T = withContext(dispatcher) {
        block()
    }
}

object ImmediateCompanionTvMainThreadDispatcher : CompanionTvMainThreadDispatcher {
    override suspend fun <T> dispatch(block: suspend () -> T): T = block()
}

/**
 * Routes already-authenticated protocol envelopes. Authentication and record decryption stay
 * in transport/crypto; this class only decodes typed payloads and invokes TV-owned seams.
 */
class CompanionTvCommandRouter(
    private val connection: CompanionTvWireConnection,
    private val controller: CompanionTvSessionController,
    private val keyHandler: CompanionTvKeyHandler = NoOpCompanionTvKeyHandler,
    private val inputHandler: CompanionTvInputHandler = NoOpCompanionTvInputHandler,
    private val openPageHandler: CompanionTvOpenPageHandler = NoOpCompanionTvOpenPageHandler,
    private val unpairHandler: CompanionTvUnpairHandler = NoOpCompanionTvUnpairHandler,
    private val onUnpairCleanup: suspend () -> Unit = {},
    private val onUnpairComplete: suspend () -> Unit = {},
    private val mainThread: CompanionTvMainThreadDispatcher =
        CoroutineCompanionTvMainThreadDispatcher(),
    private val eventSubscription: CompanionTvEventSubscription = CompanionTvEventSubscription(),
    private val trackSelectionHandler: CompanionTvTrackSelectionHandler =
        NoOpCompanionTvTrackSelectionHandler,
    private val syncEngine: CompanionSyncEngine? = null,
    private val currentAccountNamespace: () -> String = { "0" },
) {
    private var connected = false

    suspend fun route(envelope: Envelope) {
        if (envelope.v != COMPANION_PROTOCOL_VERSION) {
            sendResult(envelope.id, ResultPayload(ok = false, error = ErrorCode.UNSUPPORTED))
            return
        }
        if (!connected) {
            controller.onPhoneConnected(connection.deviceId)
            connected = true
        }

        when (envelope.type) {
            MessageType.PING -> sendResult(envelope.id, ResultPayload(ok = true))
            MessageType.PLAY -> decodeAndRoute(envelope, PlayRequest.serializer()) { request ->
                mainThread.dispatch {
                    controller.handlePlay(connection.deviceId, envelope.id, request)
                }
            }
            MessageType.PLAYER_CMD ->
                decodeAndRoute(envelope, PlayerCommand.serializer()) { command ->
                    mainThread.dispatch {
                        controller.handlePlayerCommand(connection.deviceId, envelope.id, command)
                    }
                }
            MessageType.KEY -> decodeAndRoute(envelope, KeyRequest.serializer()) { request ->
                val handled = mainThread.dispatch { keyHandler.onKey(request.keyCode) }
                sendResult(
                    envelope.id,
                    if (handled) ResultPayload(ok = true) else {
                        ResultPayload(ok = false, error = ErrorCode.UNSUPPORTED)
                    },
                )
            }
            MessageType.INPUT_TEXT ->
                decodeAndRoute(envelope, InputTextRequest.serializer()) { request ->
                    val handled = mainThread.dispatch { inputHandler.onInputText(request.text) }
                    sendResult(
                        envelope.id,
                        if (handled) ResultPayload(ok = true) else {
                            ResultPayload(ok = false, error = ErrorCode.UNSUPPORTED)
                        },
                    )
                }
            MessageType.SELECT_SOURCE ->
                decodeAndRoute(envelope, SelectSourceRequest.serializer()) { request ->
                    val handled = mainThread.dispatch {
                        trackSelectionHandler.selectSource(request.lineageId, request.index)
                    }
                    sendResult(envelope.id, selectionResult(handled))
                }
            MessageType.SELECT_AUDIO ->
                decodeAndRoute(envelope, SelectAudioRequest.serializer()) { request ->
                    val handled = mainThread.dispatch {
                        trackSelectionHandler.selectAudio(request.lineageId, request.index)
                    }
                    sendResult(envelope.id, selectionResult(handled))
                }
            MessageType.SELECT_SUBTITLE ->
                decodeAndRoute(envelope, SelectSubtitleRequest.serializer()) { request ->
                    val handled = mainThread.dispatch {
                        trackSelectionHandler.selectSubtitle(request.lineageId, request.index)
                    }
                    sendResult(envelope.id, selectionResult(handled))
                }
            MessageType.SYNC_REQUEST ->
                decodeAndRoute(envelope, SyncRequestPayload.serializer()) { request ->
                    val engine = syncEngine
                    val account = currentAccountNamespace()
                    if (request.accountNamespace != account || request.mediaId <= 0) {
                        sendResult(
                            envelope.id,
                            ResultPayload(ok = false, error = ErrorCode.INVALID_PAYLOAD),
                        )
                        return@decodeAndRoute
                    }
                    val field = when (request.field) {
                        com.lagradost.cloudstream3.companion.protocol.SyncField.VIDEO_POS_DUR ->
                            CompanionSyncField.VIDEO_POS_DUR
                        com.lagradost.cloudstream3.companion.protocol.SyncField.VIDEO_WATCH_STATE ->
                            CompanionSyncField.VIDEO_WATCH_STATE
                    }
                    if (engine != null) {
                        val record = engine.read(account, field, request.mediaId)
                        if (record != null) {
                            sendEvent(
                                Event(
                                    kind = EventKind.SYNC_RECORD,
                                    syncRecord = record.toPayload(),
                                ),
                            )
                        }
                    }
                    sendResult(envelope.id, ResultPayload(ok = true))
                }
            MessageType.SYNC_PUSH ->
                decodeAndRoute(envelope, SyncRecordPayload.serializer()) { payload ->
                    val result = syncEngine?.apply(
                        currentAccountNamespace(),
                        payload.toSyncRecord(),
                    )
                    val response = when {
                        result == null ->
                            ResultPayload(ok = false, error = ErrorCode.UNSUPPORTED)
                        result is CompanionSyncApplyResult.Rejected ->
                            ResultPayload(ok = false, error = ErrorCode.INVALID_PAYLOAD)
                        else -> ResultPayload(ok = true)
                    }
                    sendResult(
                        envelope.id,
                        response,
                    )
                }
            MessageType.SUBSCRIBE -> {
                eventSubscription.enabled = true
                sendResult(envelope.id, ResultPayload(ok = true))
            }
            MessageType.OPEN_PAGE ->
                decodeAndRoute(envelope, OpenPageRequest.serializer()) { request ->
                    val handled = mainThread.dispatch {
                        openPageHandler.openPage(request.apiName, request.url)
                    }
                    sendResult(
                        envelope.id,
                        if (handled) ResultPayload(ok = true) else {
                            ResultPayload(ok = false, error = ErrorCode.UNSUPPORTED)
                        },
                    )
            }
            MessageType.UNPAIR -> {
                val handled = mainThread.dispatch { unpairHandler.unpair(connection.deviceId) }
                if (handled) {
                    // Cleanup owns player state and must finish before revocation closes the
                    // socket. Always run completion and disconnect even if cleanup or the result
                    // write fails, so UNPAIR cannot strand the controller/session state.
                    try {
                        onUnpairCleanup()
                        sendResult(envelope.id, ResultPayload(ok = true))
                    } catch (_: Throwable) {
                        runCatching {
                            sendResult(
                                envelope.id,
                                ResultPayload(ok = false, error = ErrorCode.INTERNAL),
                            )
                        }
                    } finally {
                        try {
                            onUnpairComplete()
                        } finally {
                            disconnect()
                        }
                    }
                } else {
                    sendResult(envelope.id, ResultPayload(ok = false, error = ErrorCode.UNSUPPORTED))
                }
            }
            MessageType.RESULT,
            MessageType.EVENT,
            -> sendResult(envelope.id, ResultPayload(ok = false, error = ErrorCode.UNSUPPORTED))
        }
    }

    suspend fun disconnect() {
        if (connected) controller.onPhoneDisconnected(connection.deviceId)
        connected = false
        eventSubscription.enabled = false
    }

    private suspend fun <T> decodeAndRoute(
        envelope: Envelope,
        serializer: kotlinx.serialization.DeserializationStrategy<T>,
        block: suspend (T) -> Unit,
    ) {
        val payload = envelope.payload
        if (payload == null) {
            sendResult(envelope.id, ResultPayload(ok = false, error = ErrorCode.INVALID_PAYLOAD))
            return
        }
        val decoded = try {
            ProtocolJson.decode(serializer, payload)
        } catch (_: Throwable) {
            sendResult(envelope.id, ResultPayload(ok = false, error = ErrorCode.INVALID_PAYLOAD))
            return
        }
        block(decoded)
    }

    private fun sendResult(requestId: String, result: ResultPayload) {
        connection.send(
            Envelope(
                id = requestId,
                type = MessageType.RESULT,
                payload = ProtocolJson.encode(ResultPayload.serializer(), result),
            ),
        )
    }

    private fun sendEvent(event: Event) {
        if (!eventSubscription.enabled) return
        connection.send(
            Envelope(
                id = UUID.randomUUID().toString(),
                type = MessageType.EVENT,
                payload = ProtocolJson.encode(Event.serializer(), event),
            ),
        )
    }

    private fun selectionResult(handled: Boolean): ResultPayload =
        if (handled) ResultPayload(ok = true) else {
            ResultPayload(ok = false, error = ErrorCode.UNSUPPORTED)
        }
}
