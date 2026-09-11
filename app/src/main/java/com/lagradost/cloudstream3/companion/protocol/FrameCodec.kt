package com.lagradost.cloudstream3.companion.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object FrameCodec {
    const val MAX_FRAME_SIZE = 1 shl 20

    fun readFrame(input: InputStream): ByteArray {
        val data = DataInputStream(input)
        val length = try {
            data.readInt()
        } catch (error: EOFException) {
            throw FrameException("missing frame length", error)
        }

        if (length < 0 || length > MAX_FRAME_SIZE) {
            throw FrameException("invalid frame length: $length")
        }

        return ByteArray(length).also { frame ->
            try {
                data.readFully(frame)
            } catch (error: EOFException) {
                throw FrameException("truncated frame", error)
            }
        }
    }

    fun writeFrame(output: OutputStream, payload: ByteArray) {
        require(payload.size <= MAX_FRAME_SIZE) {
            "frame payload exceeds $MAX_FRAME_SIZE bytes"
        }
        val data = DataOutputStream(output)
        data.writeInt(payload.size)
        data.write(payload)
        data.flush()
    }
}

class FrameException(message: String, cause: Throwable? = null) : IOException(message, cause)

