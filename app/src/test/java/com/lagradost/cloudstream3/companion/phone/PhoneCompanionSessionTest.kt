package com.lagradost.cloudstream3.companion.phone

import com.lagradost.cloudstream3.companion.protocol.Envelope
import com.lagradost.cloudstream3.companion.protocol.Event
import com.lagradost.cloudstream3.companion.protocol.EventKind
import com.lagradost.cloudstream3.companion.protocol.InputContext
import com.lagradost.cloudstream3.companion.protocol.InputContextKind
import com.lagradost.cloudstream3.companion.protocol.MessageType
import com.lagradost.cloudstream3.companion.protocol.NavRequested
import com.lagradost.cloudstream3.companion.protocol.NavigationDirection
import com.lagradost.cloudstream3.companion.protocol.PlaybackState
import com.lagradost.cloudstream3.companion.protocol.PlaybackStateKind
import com.lagradost.cloudstream3.companion.protocol.PlayRequest
import com.lagradost.cloudstream3.companion.protocol.PlayerAction
import com.lagradost.cloudstream3.companion.protocol.PlayerCommand
import com.lagradost.cloudstream3.companion.protocol.ProtocolJson
import com.lagradost.cloudstream3.companion.protocol.ResultPayload
import com.lagradost.cloudstream3.companion.transport.CompanionEndpoint
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCompanionSessionTest {
    @Test
    fun `authenticated connection subscribes and routes now playing and input events`() = runBlocking {
        val wire = FakeWire()
        val states = mutableListOf<PhoneSessionState>()
        val nowPlaying = mutableListOf<PhoneNowPlaying>()
        val inputs = mutableListOf<InputContext>()
        val session = newSession(
            wire = wire,
            onStateChanged = { states += it },
            onNowPlaying = { nowPlaying += it },
            onInputContext = { inputs += it },
        )
        session.connect(CompanionEndpoint("tv", 46_899))
        waitFor { wire.outgoing.any { it.type == MessageType.SUBSCRIBE } }

        wire.emit(
            eventEnvelope(
                Event(
                    kind = EventKind.PLAYBACK_STATE,
                    playbackState = PlaybackState(
                        title = "Episode",
                        positionMs = 123,
                        durationMs = 456,
                        state = PlaybackStateKind.PLAYING,
                    ),
                )
            )
        )
        wire.emit(
            eventEnvelope(
                Event(
                    kind = EventKind.INPUT_CONTEXT,
                    inputContext = InputContext(InputContextKind.SEARCH_FIELD, "abc"),
                )
            )
        )
        waitFor { nowPlaying.size == 1 && inputs.size == 1 }
        assertEquals(PhoneSessionState.CONNECTED, states.last())
        assertEquals(123, nowPlaying.single().state.positionMs)
        assertEquals("abc", inputs.single().currentText)
        session.disconnect()
    }

    @Test
    fun `play sends resolved request and stale navigation is ignored`() = runBlocking {
        val wire = FakeWire()
        val navigation = mutableListOf<PhoneNavigationRequest>()
        val session = newSession(wire = wire, onNavigationRequested = { navigation += it })
        session.connect(CompanionEndpoint("tv", 46_899))
        waitFor { wire.outgoing.any { it.type == MessageType.SUBSCRIBE } }

        val lineage = session.play(
            LinkResolutionInput(
                links = listOf(
                    ExtractorLink(
                        "source", "name", "https://cdn.example/video.mp4", "", 720,
                        type = ExtractorLinkType.VIDEO,
                    )
                ),
                subtitles = emptyList(),
                title = "Episode",
                lineageId = "placeholder",
            )
        )
        waitFor { wire.outgoing.any { it.type == MessageType.PLAY } }
        val playEnvelope = wire.outgoing.last { it.type == MessageType.PLAY }
        val play = ProtocolJson.decode(PlayRequest.serializer(), playEnvelope.payload!!)
        assertEquals(lineage, play.lineageId)
        assertEquals(0, play.attempt)

        wire.emit(
            eventEnvelope(
                Event(
                    kind = EventKind.NAV_REQUESTED,
                    navRequested = NavRequested("other", NavigationDirection.NEXT),
                )
            )
        )
        delay(25)
        assertTrue(navigation.isEmpty())
        wire.emit(
            eventEnvelope(
                Event(
                    kind = EventKind.NAV_REQUESTED,
                    navRequested = NavRequested(lineage, NavigationDirection.NEXT),
                )
            )
        )
        waitFor { navigation.size == 1 }
        assertEquals(NavigationDirection.NEXT, navigation.single().direction)
        session.disconnect()
    }

    @Test
    fun `player controls send typed protocol envelopes`() = runBlocking {
        val wire = FakeWire()
        val session = newSession(wire)
        session.connect(CompanionEndpoint("tv", 46_899))
        waitFor { wire.outgoing.any { it.type == MessageType.SUBSCRIBE } }
        assertTrue(session.sendPlayerCommand(PlayerCommand(PlayerAction.PAUSE)))
        assertTrue(session.sendKey(19))
        assertTrue(session.sendInputText("query"))
        assertEquals(
            listOf(MessageType.SUBSCRIBE, MessageType.PLAYER_CMD, MessageType.KEY, MessageType.INPUT_TEXT),
            wire.outgoing.map { it.type },
        )
        session.disconnect()
    }

    private fun CoroutineScope.newSession(
        wire: FakeWire,
        onStateChanged: (PhoneSessionState) -> Unit = {},
        onNowPlaying: (PhoneNowPlaying) -> Unit = {},
        onInputContext: (InputContext) -> Unit = {},
        onNavigationRequested: (PhoneNavigationRequest) -> Unit = {},
    ) = PhoneCompanionSession(
        scope = this,
        dialer = PhoneSessionDialer { wire },
        pipeline = LinkResolutionPipeline(
            probe = object : CandidateProbe {
                override suspend fun get(
                    url: String,
                    headers: Map<String, String>,
                    range: String?,
                ) = ProbeResponse(206, url, "video/mp4", byteArrayOf(1))
            },
            clock = CompanionClock { 1_000L },
        ),
        clock = CompanionClock { 1_000L },
        onStateChanged = onStateChanged,
        onNowPlaying = onNowPlaying,
        onInputContext = onInputContext,
        onNavigationRequested = onNavigationRequested,
    )

    private suspend fun waitFor(predicate: () -> Boolean) {
        withTimeout(2_000L) {
            while (!predicate()) delay(1)
        }
    }

    private fun eventEnvelope(event: Event) = Envelope(
        id = "event-${event.kind}",
        type = MessageType.EVENT,
        payload = ProtocolJson.encode(Event.serializer(), event),
    )

    private class FakeWire : PhoneWireConnection {
        val outgoing = mutableListOf<Envelope>()
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

        override suspend fun readFrame(): ByteArray = incoming.receive()

        override suspend fun writeFrame(payload: ByteArray) {
            val envelope = ProtocolJson.decodeEnvelope(payload)
            outgoing += envelope
            incoming.send(
                ProtocolJson.encodeEnvelope(
                    Envelope(
                        id = envelope.id,
                        type = MessageType.RESULT,
                        payload = ProtocolJson.encode(
                            ResultPayload.serializer(),
                            ResultPayload(ok = true),
                        ),
                    )
                )
            )
        }

        fun emit(envelope: Envelope) {
            incoming.trySend(ProtocolJson.encodeEnvelope(envelope))
        }

        override fun close() {
            incoming.close()
        }
    }
}
