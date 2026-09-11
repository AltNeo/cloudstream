package com.lagradost.cloudstream3.companion.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameCodecTest {
    @Test
    fun `oversized frame is rejected before allocation`() {
        val header = byteArrayOf(0, 0x10, 0, 1)
        assertThrows(FrameException::class.java) {
            FrameCodec.readFrame(ByteArrayInputStream(header))
        }
    }

    @Test
    fun `oversized payload cannot be written`() {
        val payload = ByteArray(FrameCodec.MAX_FRAME_SIZE + 1)
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.writeFrame(ByteArrayOutputStream(), payload)
        }
    }

    @Test
    fun `truncated frame is reported as a frame error`() {
        val bytes = byteArrayOf(0, 0, 0, 4, 1, 2)
        assertThrows(FrameException::class.java) {
            FrameCodec.readFrame(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `maximum size frame is accepted`() {
        val payload = ByteArray(FrameCodec.MAX_FRAME_SIZE) { it.toByte() }
        val output = ByteArrayOutputStream()
        FrameCodec.writeFrame(output, payload)
        val restored = FrameCodec.readFrame(ByteArrayInputStream(output.toByteArray()))
        org.junit.Assert.assertArrayEquals(payload, restored)
    }

    @Test
    fun `negative frame length is rejected`() {
        val header = byteArrayOf(-1, -1, -1, -1)
        assertThrows(FrameException::class.java) {
            FrameCodec.readFrame(ByteArrayInputStream(header))
        }
    }
}
