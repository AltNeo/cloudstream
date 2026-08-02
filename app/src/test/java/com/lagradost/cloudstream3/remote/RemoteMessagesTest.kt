package com.lagradost.cloudstream3.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class RemoteMessagesTest {
    @Test
    fun `envelope survives framed round trip`() {
        val envelope = RemoteEnvelope(
            requestId = "request-1",
            deviceId = "device-1",
            timestampMs = 12345L,
            auth = RemoteAuth.sign("token", "request-1", 12345L),
            type = RemoteMessageType.HELLO,
            payload = encodePayload(DeviceInfo("device-1", "TV", "1.0", 2, true, true)),
        )
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
        RemoteMessageType.entries.forEach { type ->
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
}
