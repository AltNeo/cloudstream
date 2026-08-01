package com.lagradost.cloudstream3.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

@Serializable
enum class LanRemoteCommand {
    PING,
    LAUNCH,
    KEY,
    TEXT,
    PLAY,
}

@Serializable
data class LanRemotePlayPayload(
    val links: List<String>,
    val subtitles: List<String> = emptyList(),
    val title: String? = null,
    val mediaId: Int? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
)

@Serializable
data class LanRemoteRequest(
    val version: Int = LanRemoteProtocol.VERSION,
    val requestId: String = UUID.randomUUID().toString(),
    val command: LanRemoteCommand,
    val keyCode: Int? = null,
    val text: String? = null,
    val play: LanRemotePlayPayload? = null,
)

@Serializable
data class LanRemoteResponse(
    val version: Int = LanRemoteProtocol.VERSION,
    val requestId: String,
    val accepted: Boolean,
    val message: String? = null,
)

object LanRemoteProtocol {
    const val VERSION = 1
    const val PORT = 46900
    const val SERVICE_TYPE = "_cloudstream-remote._tcp."
    const val MAX_FRAME_BYTES = 1024 * 1024

    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    inline fun <reified T> read(input: DataInputStream): T {
        val size = input.readInt()
        require(size in 1..MAX_FRAME_BYTES) { "Invalid frame size: $size" }
        val payload = ByteArray(size)
        input.readFully(payload)
        return json.decodeFromString(payload.decodeToString())
    }

    inline fun <reified T> write(output: DataOutputStream, value: T) {
        val payload = json.encodeToString(value).encodeToByteArray()
        require(payload.size <= MAX_FRAME_BYTES) { "Frame is too large" }
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }
}
