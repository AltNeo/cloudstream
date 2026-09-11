package com.lagradost.cloudstream3.companion.tv

import com.lagradost.cloudstream3.companion.protocol.Envelope
import com.lagradost.cloudstream3.companion.protocol.ErrorCode
import com.lagradost.cloudstream3.companion.protocol.Event
import com.lagradost.cloudstream3.companion.protocol.EventKind
import com.lagradost.cloudstream3.companion.protocol.InputTextRequest
import com.lagradost.cloudstream3.companion.protocol.KeyRequest
import com.lagradost.cloudstream3.companion.protocol.MessageType
import com.lagradost.cloudstream3.companion.protocol.OpenPageRequest
import com.lagradost.cloudstream3.companion.protocol.PlayRequest
import com.lagradost.cloudstream3.companion.protocol.ProtocolJson
import com.lagradost.cloudstream3.companion.protocol.ResolvedLink
import com.lagradost.cloudstream3.companion.protocol.ResultPayload
import com.lagradost.cloudstream3.companion.protocol.SelectAudioRequest
import com.lagradost.cloudstream3.companion.protocol.SelectSourceRequest
import com.lagradost.cloudstream3.companion.protocol.SelectSubtitleRequest
import com.lagradost.cloudstream3.companion.protocol.SyncField
import com.lagradost.cloudstream3.companion.protocol.SyncRecordPayload
import com.lagradost.cloudstream3.companion.protocol.SyncRequestPayload
import com.lagradost.cloudstream3.companion.sync.CompanionDataStoreHelperAdapter
import com.lagradost.cloudstream3.companion.sync.CompanionSyncEngine
import com.lagradost.cloudstream3.companion.sync.StoredCompanionPosition
import com.lagradost.cloudstream3.companion.sync.StoredCompanionWatchState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionTvCommandRouterTest {
    @Test
    fun `ping and subscription use result and event envelopes`() = runBlocking {
        val connection = RecordingConnection()
        val subscription = CompanionTvEventSubscription()
        val sink = CompanionTvWireSessionSink(connection, subscription)
        val router = CompanionTvCommandRouter(
            connection = connection,
            controller = controller(sink),
            mainThread = ImmediateCompanionTvMainThreadDispatcher,
            eventSubscription = subscription,
        )

        router.route(Envelope(id = "ping", type = MessageType.PING))
        assertEquals(MessageType.RESULT, connection.sent.single().type)
        assertEquals(
            ResultPayload(ok = true),
            ProtocolJson.decode(ResultPayload.serializer(), connection.sent.single().payload!!),
        )

        router.route(Envelope(id = "subscribe", type = MessageType.SUBSCRIBE))
        assertTrue(subscription.enabled)
        assertEquals(2, connection.sent.size)
    }

    @Test
    fun `key and input messages dispatch on the supplied main thread`() = runBlocking {
        val connection = RecordingConnection()
        var keyCode = 0
        var text = ""
        var dispatches = 0
        val router = CompanionTvCommandRouter(
            connection = connection,
            controller = controller(CompanionTvWireSessionSink(connection)),
            keyHandler = object : CompanionTvKeyHandler {
                override fun onKey(value: Int): Boolean {
                    keyCode = value
                    return true
                }
            },
            inputHandler = object : CompanionTvInputHandler {
                override fun onInputText(value: String): Boolean {
                    text = value
                    return true
                }
            },
            mainThread = object : CompanionTvMainThreadDispatcher {
                override suspend fun <T> dispatch(block: suspend () -> T): T {
                    dispatches += 1
                    return block()
                }
            },
        )

        router.route(
            Envelope(
                id = "key",
                type = MessageType.KEY,
                payload = ProtocolJson.encode(KeyRequest.serializer(), KeyRequest(19)),
            ),
        )
        router.route(
            Envelope(
                id = "input",
                type = MessageType.INPUT_TEXT,
                payload = ProtocolJson.encode(
                    InputTextRequest.serializer(),
                    InputTextRequest("search"),
                ),
            ),
        )

        assertEquals(19, keyCode)
        assertEquals("search", text)
        assertEquals(2, dispatches)
        assertEquals(2, connection.sent.size)
    }

    @Test
    fun `malformed payload is rejected before handler invocation`() = runBlocking {
        val connection = RecordingConnection()
        val router = CompanionTvCommandRouter(
            connection = connection,
            controller = controller(CompanionTvWireSessionSink(connection)),
            mainThread = ImmediateCompanionTvMainThreadDispatcher,
        )

        router.route(Envelope(id = "bad", type = MessageType.PLAY))
        val result = ProtocolJson.decode(
            ResultPayload.serializer(),
            connection.sent.single().payload!!,
        )
        assertEquals(ResultPayload(ok = false, error = ErrorCode.INVALID_PAYLOAD), result)
    }

    @Test
    fun `open page and unpair use injectable hooks`() = runBlocking {
        val connection = RecordingConnection()
        var openedUrl = ""
        var unpairedDevice = ""
        val router = CompanionTvCommandRouter(
            connection = connection,
            controller = controller(CompanionTvWireSessionSink(connection)),
            openPageHandler = object : CompanionTvOpenPageHandler {
                override fun openPage(apiName: String, url: String): Boolean {
                    openedUrl = "$apiName:$url"
                    return true
                }
            },
            unpairHandler = object : CompanionTvUnpairHandler {
                override fun unpair(deviceId: String): Boolean {
                    unpairedDevice = deviceId
                    return true
                }
            },
            mainThread = ImmediateCompanionTvMainThreadDispatcher,
        )

        router.route(
            Envelope(
                id = "open",
                type = MessageType.OPEN_PAGE,
                payload = ProtocolJson.encode(
                    OpenPageRequest.serializer(),
                    OpenPageRequest("search", "https://example.test/search"),
                ),
            ),
        )
        router.route(Envelope(id = "unpair", type = MessageType.UNPAIR))

        assertEquals("search:https://example.test/search", openedUrl)
        assertEquals("phone", unpairedDevice)
        assertEquals(2, connection.sent.size)
    }

    @Test
    fun `unpair cleans controller before completion callback`() = runBlocking {
        val connection = RecordingConnection()
        val order = mutableListOf<String>()
        val router = CompanionTvCommandRouter(
            connection = connection,
            controller = controller(CompanionTvWireSessionSink(connection)),
            unpairHandler = object : CompanionTvUnpairHandler {
                override fun unpair(deviceId: String): Boolean = true
            },
            onUnpairCleanup = { order += "cleanup" },
            onUnpairComplete = { order += "complete" },
            mainThread = ImmediateCompanionTvMainThreadDispatcher,
        )

        router.route(Envelope(id = "unpair", type = MessageType.UNPAIR))

        assertEquals(listOf("cleanup", "complete"), order)
        assertEquals(ResultPayload(ok = true), ProtocolJson.decode(
            ResultPayload.serializer(),
            connection.sent.single().payload!!,
        ))
    }

    @Test
    fun `track selection uses typed requests and main thread seam`() = runBlocking {
        val connection = RecordingConnection()
        val selections = mutableListOf<String>()
        val router = CompanionTvCommandRouter(
            connection = connection,
            controller = controller(CompanionTvWireSessionSink(connection)),
            trackSelectionHandler = object : CompanionTvTrackSelectionHandler {
                override fun selectSource(lineageId: String?, index: Int): Boolean {
                    selections += "source:$lineageId:$index"
                    return true
                }

                override fun selectAudio(lineageId: String?, index: Int): Boolean {
                    selections += "audio:$lineageId:$index"
                    return true
                }

                override fun selectSubtitle(lineageId: String?, index: Int?): Boolean {
                    selections += "subtitle:$lineageId:$index"
                    return true
                }
            },
            mainThread = ImmediateCompanionTvMainThreadDispatcher,
        )

        router.route(
            Envelope(
                id = "source",
                type = MessageType.SELECT_SOURCE,
                payload = ProtocolJson.encode(
                    SelectSourceRequest.serializer(),
                    SelectSourceRequest(3, "lineage"),
                ),
            ),
        )
        router.route(
            Envelope(
                id = "audio",
                type = MessageType.SELECT_AUDIO,
                payload = ProtocolJson.encode(
                    SelectAudioRequest.serializer(),
                    SelectAudioRequest(2, "lineage"),
                ),
            ),
        )
        router.route(
            Envelope(
                id = "subtitle",
                type = MessageType.SELECT_SUBTITLE,
                payload = ProtocolJson.encode(
                    SelectSubtitleRequest.serializer(),
                    SelectSubtitleRequest(null, "lineage"),
                ),
            ),
        )

        assertEquals(
            listOf("source:lineage:3", "audio:lineage:2", "subtitle:lineage:null"),
            selections,
        )
        assertEquals(3, connection.sent.size)
        connection.sent.forEach { envelope ->
            assertEquals(
                ResultPayload(ok = true),
                ProtocolJson.decode(ResultPayload.serializer(), envelope.payload!!),
            )
        }
    }

    @Test
    fun `sync push applies typed record and request returns an event`() = runBlocking {
        val connection = RecordingConnection()
        val subscription = CompanionTvEventSubscription()
        val adapter = RecordingSyncAdapter()
        val router = CompanionTvCommandRouter(
            connection = connection,
            controller = controller(CompanionTvWireSessionSink(connection, subscription)),
            eventSubscription = subscription,
            syncEngine = CompanionSyncEngine(adapter) { 10_000L },
            currentAccountNamespace = { "0" },
            mainThread = ImmediateCompanionTvMainThreadDispatcher,
        )
        router.route(Envelope(id = "subscribe", type = MessageType.SUBSCRIBE))

        val record = SyncRecordPayload(
            accountNamespace = "0",
            field = SyncField.VIDEO_POS_DUR,
            mediaId = 12,
            positionMs = 5_000L,
            durationMs = 60_000L,
            updatedAtMs = 9_000L,
        )
        router.route(
            Envelope(
                id = "push",
                type = MessageType.SYNC_PUSH,
                payload = ProtocolJson.encode(SyncRecordPayload.serializer(), record),
            ),
        )
        assertEquals(5_000L, adapter.position?.positionMs)
        assertEquals(
            ResultPayload(ok = true),
            ProtocolJson.decode(
                ResultPayload.serializer(),
                connection.sent.last().payload!!,
            ),
        )

        router.route(
            Envelope(
                id = "request",
                type = MessageType.SYNC_REQUEST,
                payload = ProtocolJson.encode(
                    SyncRequestPayload.serializer(),
                    SyncRequestPayload("0", SyncField.VIDEO_POS_DUR, 12),
                ),
            ),
        )
        val event = connection.sent.drop(2).first { it.type == MessageType.EVENT }
        assertEquals(
            record,
            ProtocolJson.decode(Event.serializer(), event.payload!!).syncRecord,
        )
        assertEquals(EventKind.SYNC_RECORD, ProtocolJson.decode(
            Event.serializer(),
            event.payload!!,
        ).kind)
    }

    private fun controller(sink: CompanionTvSessionSink) = CompanionTvSessionController(
        launcher = object : TvPlaybackLauncher {
            override suspend fun startCandidate(
                request: PlayRequest,
                candidate: ResolvedLink,
                startPositionMs: Long?,
                deadlineMs: Long,
            ) = PlaybackStartResult.Started

            override fun stop() = Unit
        },
        sink = sink,
    )
}

private class RecordingConnection : CompanionTvWireConnection {
    override val deviceId: String = "phone"
    val sent = mutableListOf<Envelope>()

    override fun send(envelope: Envelope) {
        sent += envelope
    }
}

private class RecordingSyncAdapter : CompanionDataStoreHelperAdapter {
    var position: StoredCompanionPosition? = null
    private var watchState: StoredCompanionWatchState? = null

    override fun readPosition(accountNamespace: String, mediaId: Int): StoredCompanionPosition? =
        position

    override fun writePosition(
        accountNamespace: String,
        mediaId: Int,
        positionMs: Long,
        durationMs: Long,
        updatedAtMs: Long,
    ) {
        position = StoredCompanionPosition(positionMs, durationMs, updatedAtMs)
    }

    override fun readWatchState(
        accountNamespace: String,
        mediaId: Int,
    ): StoredCompanionWatchState? = watchState

    override fun writeWatchState(
        accountNamespace: String,
        mediaId: Int,
        state: com.lagradost.cloudstream3.companion.sync.CompanionWatchState,
        updatedAtMs: Long,
    ) {
        watchState = StoredCompanionWatchState(state, updatedAtMs)
    }
}
