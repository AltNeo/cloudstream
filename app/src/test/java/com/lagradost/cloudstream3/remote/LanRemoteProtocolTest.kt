package com.lagradost.cloudstream3.remote

import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

class LanRemoteProtocolTest {
    @Test
    fun `request survives a framed round trip`() {
        val request = LanRemoteRequest(
            requestId = "request-1",
            command = LanRemoteCommand.PLAY,
            play = LanRemotePlayPayload(
                links = listOf("{\"url\":\"https://example.com/video.m3u8\"}"),
                subtitles = listOf("{\"url\":\"https://example.com/subtitles.vtt\"}"),
                title = "Example",
                mediaId = 42,
                positionMs = 1_000,
                durationMs = 10_000,
            ),
        )
        val bytes = ByteArrayOutputStream().also { output ->
            LanRemoteProtocol.write(DataOutputStream(output), request)
        }.toByteArray()

        val decoded = LanRemoteProtocol.read<LanRemoteRequest>(
            DataInputStream(ByteArrayInputStream(bytes))
        )

        assertEquals(request, decoded)
    }

    @Test
    fun `response survives a framed round trip`() {
        val response = LanRemoteResponse(
            requestId = "request-2",
            accepted = true,
            message = "TV",
        )
        val bytes = ByteArrayOutputStream().also { output ->
            LanRemoteProtocol.write(DataOutputStream(output), response)
        }.toByteArray()

        val decoded = LanRemoteProtocol.read<LanRemoteResponse>(
            DataInputStream(ByteArrayInputStream(bytes))
        )

        assertEquals(response, decoded)
    }

    @Test
    fun `invalid frame size is rejected`() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).writeInt(LanRemoteProtocol.MAX_FRAME_BYTES + 1)
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            LanRemoteProtocol.read<LanRemoteRequest>(DataInputStream(ByteArrayInputStream(bytes)))
        }
    }

    @Test
    fun `oversized outgoing frame is rejected`() {
        val request = LanRemoteRequest(
            command = LanRemoteCommand.TEXT,
            text = "x".repeat(LanRemoteProtocol.MAX_FRAME_BYTES),
        )

        assertThrows(IllegalArgumentException::class.java) {
            LanRemoteProtocol.write(DataOutputStream(ByteArrayOutputStream()), request)
        }
    }

    @Test
    fun `live tv survives malformed client and responds to ping`() {
        val host = System.getenv("LAN_REMOTE_TV").orEmpty()
        assumeTrue("LAN_REMOTE_TV was not set", host.isNotBlank())

        Socket(host, LanRemoteProtocol.PORT).use { }
        Thread.sleep(300)

        val requests = listOf(
            LanRemoteRequest(requestId = "live-ping-1", command = LanRemoteCommand.PING),
            LanRemoteRequest(requestId = "live-ping-2", command = LanRemoteCommand.PING),
            LanRemoteRequest(requestId = "live-launch", command = LanRemoteCommand.LAUNCH),
            LanRemoteRequest(
                requestId = "live-dpad-down",
                command = LanRemoteCommand.KEY,
                keyCode = 20,
            ),
            LanRemoteRequest(
                requestId = "live-play",
                command = LanRemoteCommand.PLAY,
                play = LanRemotePlayPayload(
                    links = listOf(
                        CloudStreamPackage.MinimalVideoLink(
                            uri = null,
                            url = "https://commondatastorage.googleapis.com/" +
                                "gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
                            name = "LAN remote test",
                            quality = 720,
                        ).toJson()
                    ),
                    title = "LAN remote test",
                    mediaId = 46900,
                ),
            ),
        )
        requests.forEach { request ->
            Socket(host, LanRemoteProtocol.PORT).use { socket ->
                socket.soTimeout = 5_000
                LanRemoteProtocol.write(DataOutputStream(socket.getOutputStream()), request)
                val response = LanRemoteProtocol.read<LanRemoteResponse>(
                    DataInputStream(socket.getInputStream())
                )
                assertEquals(request.requestId, response.requestId)
                assertTrue(response.accepted)
            }
        }
    }
}
