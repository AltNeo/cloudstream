package com.lagradost.cloudstream3.remote

import com.lagradost.cloudstream3.remote.server.CommandHandlers
import com.lagradost.cloudstream3.remote.server.NowPlayingHub
import com.lagradost.cloudstream3.remote.server.SubscriberEventQueue
import kotlin.concurrent.thread
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

class RemoteMessagesTest {

    @Test
    fun `LAN endpoint parser preserves IPv6 literals`() {
        assertEquals("2001:db8::1" to 1234, parseLanRemoteAddress("[2001:db8::1]:1234"))
        assertEquals("2001:db8::1" to LanRemoteProtocol.PORT, parseLanRemoteAddress("2001:db8::1"))
        assertEquals("192.168.1.2" to 4321, parseLanRemoteAddress("192.168.1.2:4321"))
    }
    @Test
    fun `envelope survives framed round trip`() {
        val unsigned = RemoteEnvelope(
            requestId = "request-1",
            deviceId = "device-1",
            timestampMs = 12345L,
            type = RemoteMessageType.HELLO,
            payload = encodePayload(DeviceInfo("device-1", "TV", "1.0", 2, true, true)),
        )
        val envelope = unsigned.copy(auth = RemoteAuth.sign("token", unsigned))
        val bytes = ByteArrayOutputStream().also { output ->
            LanRemoteProtocol.write(DataOutputStream(output), envelope)
        }.toByteArray()

        val decoded = LanRemoteProtocol.read<RemoteEnvelope>(
            DataInputStream(ByteArrayInputStream(bytes))
        )

        assertEquals(envelope, decoded)
        assertEquals(
            DeviceInfo("device-1", "TV", "1.0", 2, true, true),
            decoded.payloadAs<DeviceInfo>(),
        )
    }

    @Test
    fun `reply survives framed round trip with payload`() {
        val reply = RemoteReply(
            requestId = "request-2",
            accepted = true,
            payload = encodePayload(PairHelloReply("session-1", 120_000)),
        )
        val bytes = ByteArrayOutputStream().also { output ->
            LanRemoteProtocol.write(DataOutputStream(output), reply)
        }.toByteArray()

        val decoded = LanRemoteProtocol.read<RemoteReply>(
            DataInputStream(ByteArrayInputStream(bytes))
        )

        assertEquals(reply, decoded)
        assertEquals(PairHelloReply("session-1", 120_000), decoded.payloadAs<PairHelloReply>())
    }

    @Test
    fun `every message type round trips`() {
        // UNKNOWN is the decode-only fallback and is deliberately non-emittable (see
        // `encoding fallback UNKNOWN values fails safely`), so it is excluded here.
        RemoteMessageType.entries.filter { it != RemoteMessageType.UNKNOWN }.forEach { type ->
            val envelope = RemoteEnvelope(type = type, payload = null)
            val bytes = ByteArrayOutputStream().also { output ->
                LanRemoteProtocol.write(DataOutputStream(output), envelope)
            }.toByteArray()
            val decoded = LanRemoteProtocol.read<RemoteEnvelope>(
                DataInputStream(ByteArrayInputStream(bytes))
            )
            assertEquals(type, decoded.type)
        }
    }

    @Test
    fun `v2 frame detection works`() {
        val v2 = LanRemoteProtocol.json.parseToJsonElement(
            LanRemoteProtocol.json.encodeToString(
                RemoteEnvelope(type = RemoteMessageType.PING)
            )
        )
        assertTrue(LanRemoteProtocol.isV2Frame(v2))

        val v1 = LanRemoteProtocol.json.parseToJsonElement(
            LanRemoteProtocol.json.encodeToString(
                LanRemoteRequest(command = LanRemoteCommand.PING)
            )
        )
        assertFalse(LanRemoteProtocol.isV2Frame(v1))
    }

    @Test
    fun `unknown fields in envelope are tolerated`() {
        val raw = """{"version":2,"requestId":"r","type":"PING","futureField":{"a":1}}"""
        val decoded = LanRemoteProtocol.json.decodeFromString<RemoteEnvelope>(raw)
        assertEquals(RemoteMessageType.PING, decoded.type)
    }

    @Test
    fun `play payload with minimal video link round trips`() {
        val play = PlayPayload(
            links = listOf(
                com.lagradost.cloudstream3.actions.temp.CloudStreamPackage.MinimalVideoLink(
                    uri = null,
                    url = "https://example.com/video.m3u8",
                    name = "720p",
                    headers = mapOf("Cookie" to "a=b"),
                    quality = 720,
                    source = "ExampleProvider",
                )
            ),
            title = "Example",
            mediaId = 42,
            positionMs = 1_000,
            durationMs = 10_000,
        )
        val envelope = RemoteEnvelope(type = RemoteMessageType.PLAY, payload = encodePayload(play))
        val decoded = envelope.payloadAs<PlayPayload>()
        assertEquals(play, decoded)
        assertEquals("ExampleProvider", decoded?.links?.first()?.source)
    }

    @Test
    fun `unknown envelope type decodes to UNKNOWN instead of failing`() {
        val raw = """{"version":2,"requestId":"r1","deviceId":"d1","type":"FUTURE_TYPE","payload":{"a":1}}"""
        val decoded = LanRemoteProtocol.json.decodeFromString<RemoteEnvelope>(raw)
        assertEquals(RemoteMessageType.UNKNOWN, decoded.type)
    }

    @Test
    fun `unknown event kind decodes to UNKNOWN and keeps nested payload`() {
        val raw = """{"kind":"FUTURE_EVENT","nowPlaying":{"title":"T","state":"PLAYING"}}"""
        val event = LanRemoteProtocol.json.decodeFromString<RemoteEvent>(raw)
        assertEquals(RemoteEvent.Kind.UNKNOWN, event.kind)
        assertEquals("T", event.nowPlaying?.title)
    }

    @Test
    fun `nested enum unknown values fall back to UNKNOWN`() {
        val cmd = LanRemoteProtocol.json.decodeFromString<PlayerCmdPayload>(
            """{"action":"FUTURE_ACTION","positionMs":5}"""
        )
        assertEquals(PlayerCmdPayload.Action.UNKNOWN, cmd.action)

        val state = LanRemoteProtocol.json.decodeFromString<NowPlayingPayload>(
            """{"title":"T","state":"FUTURE_STATE"}"""
        )
        assertEquals(NowPlayingPayload.State.UNKNOWN, state.state)

        val sync = LanRemoteProtocol.json.decodeFromString<PluginSyncResult>(
            """{"internalName":"x","status":"FUTURE_STATUS"}"""
        )
        assertEquals(PluginSyncResult.Status.UNKNOWN, sync.status)
    }

    @Test
    fun `known message types and nested enums round trip`() {
        RemoteMessageType.entries.filter { it != RemoteMessageType.UNKNOWN }.forEach { type ->
            val decoded = LanRemoteProtocol.json.decodeFromString<RemoteEnvelope>(
                LanRemoteProtocol.json.encodeToString(RemoteEnvelope(type = type))
            )
            assertEquals(type, decoded.type)
        }
        RemoteEvent.Kind.entries.filter { it != RemoteEvent.Kind.UNKNOWN }.forEach { kind ->
            val decoded = LanRemoteProtocol.json.decodeFromString<RemoteEvent>(
                LanRemoteProtocol.json.encodeToString(RemoteEvent(kind = kind))
            )
            assertEquals(kind, decoded.kind)
        }
        PlayerCmdPayload.Action.entries.filter { it != PlayerCmdPayload.Action.UNKNOWN }.forEach { action ->
            val decoded = LanRemoteProtocol.json.decodeFromString<PlayerCmdPayload>(
                LanRemoteProtocol.json.encodeToString(PlayerCmdPayload(action = action))
            )
            assertEquals(action, decoded.action)
        }
        NowPlayingPayload.State.entries.filter { it != NowPlayingPayload.State.UNKNOWN }.forEach { state ->
            val decoded = LanRemoteProtocol.json.decodeFromString<NowPlayingPayload>(
                LanRemoteProtocol.json.encodeToString(NowPlayingPayload(state = state))
            )
            assertEquals(state, decoded.state)
        }
        PluginSyncResult.Status.entries.filter { it != PluginSyncResult.Status.UNKNOWN }.forEach { status ->
            val decoded = LanRemoteProtocol.json.decodeFromString<PluginSyncResult>(
                LanRemoteProtocol.json.encodeToString(
                    PluginSyncResult(internalName = "x", status = status)
                )
            )
            assertEquals(status, decoded.status)
        }
    }

    @Test
    fun `capability constants are defined`() {
        assertEquals("input-text", DeviceInfo.CAP_INPUT_TEXT)
        assertEquals("input-context", DeviceInfo.CAP_INPUT_CONTEXT)
        assertEquals("tracks", DeviceInfo.CAP_TRACKS)
        assertEquals("playback-choices", DeviceInfo.CAP_PLAYBACK_CHOICES)
    }

    @Test
    fun `select track client encoder preserves renderer selection`() {
        val selection = SelectTrackPayload(SelectTrackPayload.TrackType.AUDIO, "audio-2")
        val encoded = LanRemoteClient.encodePayloadForMessage(
            RemoteMessageType.SELECT_TRACK,
            selection,
        )
        assertEquals(selection, encoded.payloadAs<SelectTrackPayload>())
    }

    @Test
    fun `unknown select track type decodes safely`() {
        val decoded = LanRemoteProtocol.json.decodeFromString<SelectTrackPayload>(
            """{"type":"FUTURE_TRACK","id":"x"}"""
        )
        assertEquals(SelectTrackPayload.TrackType.UNKNOWN, decoded.type)
    }

    @Test
    fun `playback choices and selection round trip`() {
        val tracks = TracksPayload(
            sources = listOf(PlaybackChoice(0, "HubDrive", "1080p")),
            currentSourceIndex = 0,
            subtitles = listOf(PlaybackChoice(0, "English", "VTT")),
            currentSubtitleIndex = 0,
        )
        assertEquals(
            tracks,
            LanRemoteProtocol.json.decodeFromString<TracksPayload>(
                LanRemoteProtocol.json.encodeToString(tracks)
            ),
        )
        val selection = SelectPlaybackOptionPayload(SelectPlaybackOptionPayload.Type.SUBTITLE, 0)
        val encoded = LanRemoteClient.encodePayloadForMessage(
            RemoteMessageType.SELECT_PLAYBACK_OPTION,
            selection,
        )
        assertEquals(selection, encoded.payloadAs<SelectPlaybackOptionPayload>())
    }

    @Test
    fun `library entry with tombstone round trips`() {
        val payload = LibrarySyncPayload(
            full = false,
            entries = listOf(
                LibraryEntry("video_pos_dur/123", """{"position":100,"duration":200}""", 1_000L),
                LibraryEntry("result_watch_state/123", null, 2_000L),
            ),
        )
        val envelope = RemoteEnvelope(
            type = RemoteMessageType.SYNC_LIBRARY,
            payload = encodePayload(payload),
        )
        val decoded = envelope.payloadAs<LibrarySyncPayload>()
        assertEquals(payload, decoded)
        assertTrue(decoded!!.entries[1].valueJson == null)
    }

    // ------------------------------------------------------------------
    // C0 input transport (INPUT_CONTEXT / INPUT_TEXT / SUBSCRIBE capabilities)
    // ------------------------------------------------------------------

    @Test
    fun `input context payload round trips with unicode text`() {
        val payload = InputContextPayload(
            context = InputContextPayload.Context.SEARCH_FIELD,
            label = "Search",
            // CJK, emoji (escaped so the source stays ASCII), combining mark, RTL Arabic.
            currentText = "\u691c\u7d22\u3053\u3093\u306b\u3061\u306f \uD83D\uDE00 e\u0301 \u0645\u0631\u062d\u0628\u0627",
        )
        val json = LanRemoteProtocol.json.encodeToString(payload)
        val decoded = LanRemoteProtocol.json.decodeFromString<InputContextPayload>(json)
        assertEquals(payload, decoded)
        assertEquals(InputContextPayload.Context.SEARCH_FIELD, decoded.context)
    }

    @Test
    fun `input context idle decodes with defaults`() {
        val raw = """{"context":"IDLE"}"""
        val decoded = LanRemoteProtocol.json.decodeFromString<InputContextPayload>(raw)
        assertEquals(InputContextPayload.Context.IDLE, decoded.context)
        assertEquals(null, decoded.currentText)
        assertEquals(null, decoded.label)
    }

    @Test
    fun `unknown input context decodes to UNKNOWN`() {
        val raw = """{"context":"FUTURE_SURFACE"}"""
        val decoded = LanRemoteProtocol.json.decodeFromString<InputContextPayload>(raw)
        assertEquals(InputContextPayload.Context.UNKNOWN, decoded.context)
    }

    @Test
    fun `input text payload round trips with unicode and empty clear`() {
        val full = InputTextPayload("\u691c\u7d22 \uD83D\uDE00 e\u0301 \u0645\u0631\u062d\u0628\u0627")
        val decodedFull = LanRemoteProtocol.json.decodeFromString<InputTextPayload>(
            LanRemoteProtocol.json.encodeToString(full)
        )
        assertEquals(full, decodedFull)

        val empty = InputTextPayload("")
        val decodedEmpty = LanRemoteProtocol.json.decodeFromString<InputTextPayload>(
            LanRemoteProtocol.json.encodeToString(empty)
        )
        assertEquals(empty, decodedEmpty)
        assertTrue(decodedEmpty.text.isEmpty())
    }

    @Test
    fun `input text envelope round trips`() {
        val envelope = RemoteEnvelope(
            type = RemoteMessageType.INPUT_TEXT,
            payload = encodePayload(InputTextPayload("\u30c6\u30b9\u30c8 \uD83D\uDE00")),
        )
        val decoded = envelope.payloadAs<InputTextPayload>()
        assertEquals("\u30c6\u30b9\u30c8 \uD83D\uDE00", decoded?.text)
    }

    @Test
    fun `input context event round trips`() {
        val event = RemoteEvent(
            kind = RemoteEvent.Kind.INPUT_CONTEXT,
            inputContext = InputContextPayload(
                context = InputContextPayload.Context.SEARCH_FIELD,
                label = "Search",
                currentText = "\u691c\u7d22",
            ),
        )
        val decoded = LanRemoteProtocol.json.decodeFromString<RemoteEvent>(
            LanRemoteProtocol.json.encodeToString(event)
        )
        assertEquals(RemoteEvent.Kind.INPUT_CONTEXT, decoded.kind)
        assertEquals(event.inputContext, decoded.inputContext)
    }

    @Test
    fun `subscribe payload round trips with capabilities`() {
        val payload = SubscribePayload(
            capabilities = setOf(DeviceInfo.CAP_INPUT_TEXT, DeviceInfo.CAP_INPUT_CONTEXT),
        )
        val decoded = LanRemoteProtocol.json.decodeFromString<SubscribePayload>(
            LanRemoteProtocol.json.encodeToString(payload)
        )
        assertEquals(payload, decoded)
    }

    @Test
    fun `legacy subscribe without capabilities decodes to empty set`() {
        // Old phone: SUBSCRIBE envelope with no payload at all.
        val envelope = LanRemoteProtocol.json.decodeFromString<RemoteEnvelope>(
            """{"type":"SUBSCRIBE","requestId":"r"}"""
        )
        assertEquals(RemoteMessageType.SUBSCRIBE, envelope.type)
        assertEquals(null, envelope.payload)
        // New phone: payload without the capabilities field still defaults to empty.
        val bare = LanRemoteProtocol.json.decodeFromString<SubscribePayload>(
            """{}"""
        )
        assertTrue(bare.capabilities.isEmpty())
    }

    @Test
    fun `subscriber capability filter gates input context`() {
        // New phone advertising the capability is allowed to see INPUT_CONTEXT.
        assertTrue(
            NowPlayingHub.canReceiveInputContext(
                setOf(DeviceInfo.CAP_INPUT_TEXT, DeviceInfo.CAP_INPUT_CONTEXT)
            )
        )
        // Legacy phone (no capabilities) and capability-less subscribers never see it.
        assertFalse(NowPlayingHub.canReceiveInputContext(emptySet()))
        assertFalse(NowPlayingHub.canReceiveInputContext(setOf(DeviceInfo.CAP_INPUT_TEXT)))
    }

    @Test
    fun `device info advertises input capabilities`() {
        val tv = DeviceInfo(
            deviceId = "d1",
            name = "TV",
            appVersion = "1.0",
            protocol = 2,
            isTv = true,
            paired = true,
            capabilities = setOf(
                DeviceInfo.CAP_EVENTS,
                DeviceInfo.CAP_EXT_SYNC,
                DeviceInfo.CAP_LIB_SYNC,
                DeviceInfo.CAP_PLAYER_CMD,
                DeviceInfo.CAP_INPUT_TEXT,
                DeviceInfo.CAP_INPUT_CONTEXT,
            ),
        )
        val decoded = LanRemoteProtocol.json.decodeFromString<DeviceInfo>(
            LanRemoteProtocol.json.encodeToString(tv)
        )
        assertEquals(tv, decoded)
        assertTrue(DeviceInfo.CAP_INPUT_TEXT in decoded.capabilities)
        assertTrue(DeviceInfo.CAP_INPUT_CONTEXT in decoded.capabilities)
    }

    // ------------------------------------------------------------------
    // Fallback enums are decode-only (never emitted) + legacy wire-name fixtures
    // ------------------------------------------------------------------

    @Test
    fun `encoding fallback UNKNOWN values fails safely`() {
        assertThrows(SerializationException::class.java) {
            LanRemoteProtocol.json.encodeToString(RemoteEnvelope(type = RemoteMessageType.UNKNOWN))
        }
        assertThrows(SerializationException::class.java) {
            LanRemoteProtocol.json.encodeToString(RemoteEvent(kind = RemoteEvent.Kind.UNKNOWN))
        }
        assertThrows(SerializationException::class.java) {
            LanRemoteProtocol.json.encodeToString(
                PlayerCmdPayload(action = PlayerCmdPayload.Action.UNKNOWN)
            )
        }
        assertThrows(SerializationException::class.java) {
            LanRemoteProtocol.json.encodeToString(
                NowPlayingPayload(state = NowPlayingPayload.State.UNKNOWN)
            )
        }
        assertThrows(SerializationException::class.java) {
            LanRemoteProtocol.json.encodeToString(
                PluginSyncResult(internalName = "x", status = PluginSyncResult.Status.UNKNOWN)
            )
        }
        assertThrows(SerializationException::class.java) {
            LanRemoteProtocol.json.encodeToString(
                InputContextPayload(context = InputContextPayload.Context.UNKNOWN)
            )
        }
    }

    @Test
    fun `legacy wire names for message types are exact`() {
        val fixtures = listOf(
            "PING" to RemoteMessageType.PING,
            "PAIR_HELLO" to RemoteMessageType.PAIR_HELLO,
            "PAIR_VERIFY" to RemoteMessageType.PAIR_VERIFY,
            "UNPAIR" to RemoteMessageType.UNPAIR,
            "HELLO" to RemoteMessageType.HELLO,
            "LAUNCH" to RemoteMessageType.LAUNCH,
            "KEY" to RemoteMessageType.KEY,
            "TEXT" to RemoteMessageType.TEXT,
            "PLAY" to RemoteMessageType.PLAY,
            "PLAYER_CMD" to RemoteMessageType.PLAYER_CMD,
            "GET_STATE" to RemoteMessageType.GET_STATE,
            "OPEN_PAGE" to RemoteMessageType.OPEN_PAGE,
            "SYNC_EXTENSIONS" to RemoteMessageType.SYNC_EXTENSIONS,
            "EXT_FILE_START" to RemoteMessageType.EXT_FILE_START,
            "EXT_FILE_CHUNK" to RemoteMessageType.EXT_FILE_CHUNK,
            "EXT_FILE_END" to RemoteMessageType.EXT_FILE_END,
            "SYNC_LIBRARY" to RemoteMessageType.SYNC_LIBRARY,
            "SUBSCRIBE" to RemoteMessageType.SUBSCRIBE,
            "EVENT" to RemoteMessageType.EVENT,
            "INPUT_TEXT" to RemoteMessageType.INPUT_TEXT,
        )
        fixtures.forEach { (wire, type) ->
            // Decoding the fixed fixture yields the known value, not the fallback.
            assertEquals(
                type,
                LanRemoteProtocol.json.decodeFromString<RemoteEnvelope>(
                    """{"type":"$wire","requestId":"r"}"""
                ).type,
            )
            // Encoding emits exactly the legacy wire name.
            val encoded = LanRemoteProtocol.json.parseToJsonElement(
                LanRemoteProtocol.json.encodeToString(RemoteEnvelope(type = type))
            ).jsonObject
            assertEquals(wire, encoded["type"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `legacy wire names for event kinds are exact`() {
        val fixtures = listOf(
            "PLAYBACK_STATE" to RemoteEvent.Kind.PLAYBACK_STATE,
            "PLAYER_GONE" to RemoteEvent.Kind.PLAYER_GONE,
            "LIBRARY_DELTA" to RemoteEvent.Kind.LIBRARY_DELTA,
            "PLUGIN_SYNC_STATUS" to RemoteEvent.Kind.PLUGIN_SYNC_STATUS,
            "PAIRING_STARTED" to RemoteEvent.Kind.PAIRING_STARTED,
            "INPUT_CONTEXT" to RemoteEvent.Kind.INPUT_CONTEXT,
        )
        fixtures.forEach { (wire, kind) ->
            assertEquals(
                kind,
                LanRemoteProtocol.json.decodeFromString<RemoteEvent>(
                    """{"kind":"$wire"}"""
                ).kind,
            )
            val encoded = LanRemoteProtocol.json.parseToJsonElement(
                LanRemoteProtocol.json.encodeToString(RemoteEvent(kind = kind))
            ).jsonObject
            assertEquals(wire, encoded["kind"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `legacy wire names for player cmd actions are exact`() {
        val fixtures = listOf(
            "PAUSE" to PlayerCmdPayload.Action.PAUSE,
            "RESUME" to PlayerCmdPayload.Action.RESUME,
            "PLAY_PAUSE" to PlayerCmdPayload.Action.PLAY_PAUSE,
            "SEEK_TO" to PlayerCmdPayload.Action.SEEK_TO,
            "SEEK_BY" to PlayerCmdPayload.Action.SEEK_BY,
            "STOP" to PlayerCmdPayload.Action.STOP,
            "SET_SPEED" to PlayerCmdPayload.Action.SET_SPEED,
            "VOLUME_UP" to PlayerCmdPayload.Action.VOLUME_UP,
            "VOLUME_DOWN" to PlayerCmdPayload.Action.VOLUME_DOWN,
            "MUTE" to PlayerCmdPayload.Action.MUTE,
        )
        fixtures.forEach { (wire, action) ->
            assertEquals(
                action,
                LanRemoteProtocol.json.decodeFromString<PlayerCmdPayload>(
                    """{"action":"$wire"}"""
                ).action,
            )
            val encoded = LanRemoteProtocol.json.parseToJsonElement(
                LanRemoteProtocol.json.encodeToString(PlayerCmdPayload(action = action))
            ).jsonObject
            assertEquals(wire, encoded["action"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `legacy wire names for now playing states are exact`() {
        val fixtures = listOf(
            "PLAYING" to NowPlayingPayload.State.PLAYING,
            "PAUSED" to NowPlayingPayload.State.PAUSED,
            "BUFFERING" to NowPlayingPayload.State.BUFFERING,
            "ENDED" to NowPlayingPayload.State.ENDED,
            "IDLE" to NowPlayingPayload.State.IDLE,
        )
        fixtures.forEach { (wire, state) ->
            assertEquals(
                state,
                LanRemoteProtocol.json.decodeFromString<NowPlayingPayload>(
                    """{"state":"$wire"}"""
                ).state,
            )
            val encoded = LanRemoteProtocol.json.parseToJsonElement(
                LanRemoteProtocol.json.encodeToString(NowPlayingPayload(state = state))
            ).jsonObject
            assertEquals(wire, encoded["state"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `legacy wire names for plugin sync statuses are exact`() {
        val fixtures = listOf(
            "OK_INSTALLED" to PluginSyncResult.Status.OK_INSTALLED,
            "OK_ALREADY" to PluginSyncResult.Status.OK_ALREADY,
            "UPDATED" to PluginSyncResult.Status.UPDATED,
            "REMOVED" to PluginSyncResult.Status.REMOVED,
            "NEWER_KEPT" to PluginSyncResult.Status.NEWER_KEPT,
            "DOWNLOAD_ONLY_SAFE_MODE" to PluginSyncResult.Status.DOWNLOAD_ONLY_SAFE_MODE,
            "FAILED" to PluginSyncResult.Status.FAILED,
        )
        fixtures.forEach { (wire, status) ->
            assertEquals(
                status,
                LanRemoteProtocol.json.decodeFromString<PluginSyncResult>(
                    """{"internalName":"x","status":"$wire"}"""
                ).status,
            )
            val encoded = LanRemoteProtocol.json.parseToJsonElement(
                LanRemoteProtocol.json.encodeToString(
                    PluginSyncResult(internalName = "x", status = status)
                )
            ).jsonObject
            assertEquals(wire, encoded["status"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `legacy wire names for input contexts are exact`() {
        val fixtures = listOf(
            "SEARCH_FIELD" to InputContextPayload.Context.SEARCH_FIELD,
            "URL_BAR" to InputContextPayload.Context.URL_BAR,
            "IDLE" to InputContextPayload.Context.IDLE,
        )
        fixtures.forEach { (wire, context) ->
            assertEquals(
                context,
                LanRemoteProtocol.json.decodeFromString<InputContextPayload>(
                    """{"context":"$wire"}"""
                ).context,
            )
            val encoded = LanRemoteProtocol.json.parseToJsonElement(
                LanRemoteProtocol.json.encodeToString(InputContextPayload(context = context))
            ).jsonObject
            assertEquals(wire, encoded["context"]?.jsonPrimitive?.content)
        }
    }

    // ------------------------------------------------------------------
    // Server-side PLAYER_CMD routing gate
    // ------------------------------------------------------------------

    @Test
    fun `player cmd with fallback UNKNOWN action is rejected before routing`() {
        // A null (malformed) command is never routed.
        assertTrue(CommandHandlers.unsupportedPlayerCmd(null))
        // A command decoded from a newer phone with an action this TV does not know
        // is rejected instead of being acknowledged as a successful no-op.
        assertTrue(
            CommandHandlers.unsupportedPlayerCmd(
                PlayerCmdPayload(action = PlayerCmdPayload.Action.UNKNOWN)
            )
        )
        // Every known action still routes.
        PlayerCmdPayload.Action.entries
            .filter { it != PlayerCmdPayload.Action.UNKNOWN }
            .forEach { action ->
                assertFalse(CommandHandlers.unsupportedPlayerCmd(PlayerCmdPayload(action = action)))
            }
    }

    // ------------------------------------------------------------------
    // Finding 1: FIFO per-subscriber event delivery (bounded queue + single writer)
    // ------------------------------------------------------------------

    @Test
    fun `subscriber queue delivers events in FIFO order`() = runBlocking {
        val queue = SubscriberEventQueue(capacity = 8)
        val seen = mutableListOf<String>()
        val writer = launch {
            while (true) {
                val event = queue.receive() ?: break
                seen.add(event.nowPlaying?.title ?: "?")
            }
        }
        (1..5).forEach { i -> assertTrue(queue.enqueue(eventWithTitle("e$i"))) }
        queue.close()
        writer.join()
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5"), seen)
    }

    @Test
    fun `subscriber queue is bounded and rejects overflow without dropping`() = runBlocking {
        val queue = SubscriberEventQueue(capacity = 2)
        assertTrue(queue.enqueue(eventWithTitle("e1")))
        assertTrue(queue.enqueue(eventWithTitle("e2")))
        assertFalse(queue.enqueue(eventWithTitle("e3")))
        assertEquals("e1", queue.receive()?.nowPlaying?.title)
        assertEquals("e2", queue.receive()?.nowPlaying?.title)
        queue.close()
        assertNull(queue.receive())
    }

    @Test
    fun `subscriber queue preserves queued frames after overflow`() = runBlocking {
        val queue = SubscriberEventQueue(capacity = 2)
        assertTrue(queue.enqueue(eventWithTitle("e1")))
        assertTrue(queue.enqueue(eventWithTitle("e2")))
        assertFalse(queue.enqueue(eventWithTitle("e3")))
        // Nothing was dropped: both queued frames are still delivered in order.
        assertEquals("e1", queue.receive()?.nowPlaying?.title)
        assertEquals("e2", queue.receive()?.nowPlaying?.title)
        queue.close()
        assertNull(queue.receive())
    }

    @Test
    fun `subscriber queue enqueue never blocks and a closed queue rejects`() {
        val queue = SubscriberEventQueue(capacity = 1)
        assertTrue(queue.enqueue(eventWithTitle("e1")))
        assertFalse(queue.enqueue(eventWithTitle("e2")))
        queue.close()
        assertFalse(queue.enqueue(eventWithTitle("e3")))
    }

    private fun eventWithTitle(title: String) = RemoteEvent(
        kind = RemoteEvent.Kind.PLAYBACK_STATE,
        nowPlaying = NowPlayingPayload(title = title),
    )

    @Test
    fun `hub replays stored input context before live events on subscribe`() {
        val server = ServerSocket(0)
        val client = Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        try {
            accepted.soTimeout = 5_000
            NowPlayingHub.broadcastInputContext(
                InputContextPayload(
                    context = InputContextPayload.Context.SEARCH_FIELD,
                    currentText = "replay",
                )
            )
            NowPlayingHub.registerSubscriber(
                "fifo-test",
                client,
                setOf(DeviceInfo.CAP_INPUT_CONTEXT),
            )
            NowPlayingHub.broadcastInputContext(
                InputContextPayload(
                    context = InputContextPayload.Context.SEARCH_FIELD,
                    currentText = "live",
                )
            )
            val input = DataInputStream(accepted.getInputStream())
            val first = LanRemoteProtocol.read<RemoteEvent>(input)
            val second = LanRemoteProtocol.read<RemoteEvent>(input)
            assertEquals("replay", first.inputContext?.currentText)
            assertEquals("live", second.inputContext?.currentText)
        } finally {
            NowPlayingHub.unregisterSubscriber("fifo-test", client)
            runCatching { client.close() }
            runCatching { accepted.close() }
            runCatching { server.close() }
        }
    }

    @Test
    fun `hub removes dead subscriber sockets after a failed write`() {
        val server = ServerSocket(0)
        val client = Socket("127.0.0.1", server.localPort)
        server.accept()
        try {
            // The socket is dead before any event: the writer's first write fails.
            client.close()
            NowPlayingHub.registerSubscriber("dead-test", client, emptySet())
            NowPlayingHub.broadcast(RemoteEvent(kind = RemoteEvent.Kind.PLAYER_GONE))
            val deadline = System.currentTimeMillis() + 5_000
            while (NowPlayingHub.subscriberCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(0, NowPlayingHub.subscriberCount())
        } finally {
            NowPlayingHub.unregisterSubscriber("dead-test", client)
            runCatching { server.close() }
        }
    }

    @Test
    fun `hub retires a slow subscriber instead of silently dropping non-replaceable frames`() {
        val server = ServerSocket(0)
        val client = Socket("127.0.0.1", server.localPort)
        server.accept()
        try {
            NowPlayingHub.registerSubscriber("slow-test", client, emptySet())
            // Block the writer inside its first socket write by holding the socket monitor, so
            // the bounded queue can actually fill (the writer takes one frame out, then blocks).
            synchronized(client) {
                NowPlayingHub.broadcast(RemoteEvent(kind = RemoteEvent.Kind.PLAYER_GONE))
                // Fill the remaining queue capacity with non-replaceable frames...
                repeat(SubscriberEventQueue.DEFAULT_CAPACITY) {
                    NowPlayingHub.broadcast(RemoteEvent(kind = RemoteEvent.Kind.PLAYER_GONE))
                }
                // ...one more overflows: the subscriber is retired (bounded, safe failure
                // policy), the frame is never silently dropped.
                NowPlayingHub.broadcast(RemoteEvent(kind = RemoteEvent.Kind.PLAYER_GONE))
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (NowPlayingHub.subscriberCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(0, NowPlayingHub.subscriberCount())
        } finally {
            NowPlayingHub.unregisterSubscriber("slow-test", client)
            runCatching { client.close() }
            runCatching { server.close() }
        }
    }

    @Test
    fun `concurrent broadcasts reach every subscriber in one deterministic order`() {
        val server = ServerSocket(0)
        val s1 = Socket("127.0.0.1", server.localPort)
        val a1 = server.accept()
        val s2 = Socket("127.0.0.1", server.localPort)
        val a2 = server.accept()
        try {
            a1.soTimeout = 5_000
            a2.soTimeout = 5_000
            NowPlayingHub.registerSubscriber("order-a", s1, emptySet())
            NowPlayingHub.registerSubscriber("order-b", s2, emptySet())
            val titles = (1..40).map { "e$it" }
            // Two threads broadcast interleaved frames: the hub lock serializes them into one
            // deterministic order, so both subscribers must see the identical sequence.
            val t1 = thread {
                titles.filterIndexed { i, _ -> i % 2 == 0 }.forEach {
                    NowPlayingHub.broadcast(eventWithTitle(it))
                }
            }
            val t2 = thread {
                titles.filterIndexed { i, _ -> i % 2 == 1 }.forEach {
                    NowPlayingHub.broadcast(eventWithTitle(it))
                }
            }
            t1.join()
            t2.join()
            fun readAll(input: DataInputStream): List<String> =
                titles.map { LanRemoteProtocol.read<RemoteEvent>(input).nowPlaying?.title!! }
            assertEquals(
                readAll(DataInputStream(a1.getInputStream())),
                readAll(DataInputStream(a2.getInputStream())),
            )
        } finally {
            NowPlayingHub.unregisterSubscriber("order-a", s1)
            NowPlayingHub.unregisterSubscriber("order-b", s2)
            runCatching { s1.close() }
            runCatching { s2.close() }
            runCatching { a1.close() }
            runCatching { a2.close() }
            runCatching { server.close() }
        }
    }

    // ------------------------------------------------------------------
    // Finding 5: whole-string input bound (before setText and before echo)
    // ------------------------------------------------------------------

    @Test
    fun `input bound accepts the documented maximum exactly`() {
        assertTrue(isInputTextWithinBound("x".repeat(MAX_INPUT_TEXT_LENGTH)))
        assertFalse(isInputTextWithinBound("x".repeat(MAX_INPUT_TEXT_LENGTH + 1)))
        assertTrue(isInputTextWithinBound(""))
    }

    @Test
    fun `input bound is whole-string and never splits unicode`() {
        // Combining sequences: 2 code units per grapheme; the bound is on code units.
        val combining = "e\u0301".repeat(MAX_INPUT_TEXT_LENGTH / 2) // exactly MAX code units
        assertTrue(isInputTextWithinBound(combining))
        // Astral chars (surrogate pairs): 2 code units each; a string at the bound is
        // accepted whole, and one pair over is rejected - never truncated mid-pair.
        val astral = "\uD83D\uDE00".repeat(MAX_INPUT_TEXT_LENGTH / 2)
        assertTrue(isInputTextWithinBound(astral))
        assertFalse(isInputTextWithinBound(astral + "\uD83D\uDE00"))
        // Mixed CJK / RTL / combining / astral text at the boundary stays within the bound
        // without any slicing.
        val mixed = "\u691c\u7d22 \u0645\u0631\u062d\u0628\u0627 e\u0301 \uD83D\uDE00"
            .repeat(MAX_INPUT_TEXT_LENGTH / 16)
        assertTrue(isInputTextWithinBound(mixed))
    }

    @Test
    fun `oversized input text is rejected before setText`() {
        // Policy gate the TV applies to authenticated INPUT_TEXT before EditText.setText:
        // an oversized replacement is refused whole, never truncated or split.
        assertFalse(isInputTextWithinBound("a".repeat(MAX_INPUT_TEXT_LENGTH + 1)))
        // A payload carrying an oversized string still round-trips (the bound is a policy
        // gate, not a transport limitation).
        val oversized = InputTextPayload("a".repeat(MAX_INPUT_TEXT_LENGTH + 1))
        val decoded = LanRemoteProtocol.json.decodeFromString<InputTextPayload>(
            LanRemoteProtocol.json.encodeToString(oversized)
        )
        assertEquals(oversized, decoded)
    }

    // ------------------------------------------------------------------
    // Finding 5b: the input bound is serialized-frame-safe (JSON escaping / control chars)
    // ------------------------------------------------------------------

    @Test
    fun `serialized input bytes match the real json escaping`() {
        val samples = listOf(
            "plain ascii",
            "\"quote\" and \\backslash\\",
            // Control characters: kotlinx.serialization emits every U+0000..U+001F as \uXXXX.
            "\u0000\u0001\u001F\n\t\r",
            // CJK, emoji (surrogate pair), combining mark, RTL Arabic.
            "\u691c\u7d22 \uD83D\uDE00 e\u0301 \u0645\u0631\u062d\u0628\u0627",
            "a".repeat(10_000),
        )
        samples.forEach { text ->
            // The payload JSON is {"text":"..."}: 8 fixed chars + quoted value + 1.
            val actual = LanRemoteProtocol.json.encodeToString(InputTextPayload(text))
                .encodeToByteArray().size
            assertTrue(serializedInputTextBytes(text) + 9 >= actual)
        }
    }

    @Test
    fun `frame bound rejects control-char strings that would overflow the frame`() {
        // All control chars at the code-unit cap expand to ~1.5 MiB under \uXXXX escaping and
        // would blow the 1 MiB frame cap; the byte gate refuses them whole.
        assertFalse(isInputTextWithinBound("\u0000".repeat(MAX_INPUT_TEXT_LENGTH)))
        assertFalse(isInputTextWithinBound("\u001F".repeat(MAX_INPUT_TEXT_LENGTH)))
        // Quotes and backslashes double in size but stay well under the byte budget at the cap.
        assertTrue(isInputTextWithinBound("\"".repeat(MAX_INPUT_TEXT_LENGTH)))
        assertTrue(isInputTextWithinBound("\\".repeat(MAX_INPUT_TEXT_LENGTH)))
        // ASCII and CJK at the code-unit cap still pass the byte gate.
        assertTrue(isInputTextWithinBound("x".repeat(MAX_INPUT_TEXT_LENGTH)))
        assertTrue(isInputTextWithinBound("\u691c".repeat(MAX_INPUT_TEXT_LENGTH)))
        // One control char over the byte budget is refused (all-or-nothing).
        val maxControl = "\u0000".repeat(MAX_INPUT_TEXT_PAYLOAD_BYTES / 6)
        assertTrue(isInputTextWithinBound(maxControl))
        assertFalse(isInputTextWithinBound(maxControl + "\u0000"))
    }

    @Test
    fun `maximal accepted input still fits the 1 MiB frame cap`() {
        // Worst escaping the byte gate still accepts: ~166k control chars (~1 MB serialized).
        val text = "\u0000".repeat(MAX_INPUT_TEXT_PAYLOAD_BYTES / 6)
        assertTrue(isInputTextWithinBound(text))
        // The full authenticated envelope (ids, timestamp, auth) written as one frame stays
        // under the frame cap - the bound is serialized-frame-safe, not just char-counted.
        val envelope = RemoteEnvelope(
            type = RemoteMessageType.INPUT_TEXT,
            payload = encodePayload(InputTextPayload(text)),
        )
        val bytes = ByteArrayOutputStream().also { output ->
            LanRemoteProtocol.write(DataOutputStream(output), envelope)
        }.toByteArray()
        assertTrue("frame was ${bytes.size} bytes", bytes.size <= LanRemoteProtocol.MAX_FRAME_BYTES)
    }

    // ------------------------------------------------------------------
    // F1 debounce policy: whole-string send gate (shouldSendInputText)
    // ------------------------------------------------------------------

    @Test
    fun `input send gate skips text already echoed by the tv`() {
        // The TV echoed exactly what the field now holds: nothing to send (feedback-loop guard).
        assertFalse(shouldSendInputText("hello", echoedCurrentText = "hello"))
        // A different string still needs sending, including an empty clear when the TV has
        // not echoed an empty field yet.
        assertTrue(shouldSendInputText("hello", echoedCurrentText = "hell"))
        assertTrue(shouldSendInputText("", echoedCurrentText = "hello"))
        assertTrue(shouldSendInputText("hello", echoedCurrentText = null))
        // Once the TV echoed the empty clear, the empty replacement is a no-op.
        assertFalse(shouldSendInputText("", echoedCurrentText = ""))
    }

    @Test
    fun `input send gate rejects oversized whole strings`() {
        // Over the bound: refused whole, even when the TV never echoed it (never truncated).
        assertFalse(
            shouldSendInputText("a".repeat(MAX_INPUT_TEXT_LENGTH + 1), echoedCurrentText = null)
        )
        // A string that fits the bound and differs from the echo still sends, including
        // Unicode text (CJK + emoji + combining mark): never sliced char-by-char.
        assertTrue(
            shouldSendInputText("\u691c\u7d22 \uD83D\uDE00 e\u0301", echoedCurrentText = "")
        )
    }
}
