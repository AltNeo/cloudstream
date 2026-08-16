package com.lagradost.cloudstream3.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

// ===========================================================================
// v1 compatibility DTOs. The v2 server still *answers* v1-shaped PING frames
// (so an old phone shows a meaningful message) and rejects everything else
// with "upgrade-required". Version policy per plan.md §3.1.
// ===========================================================================

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
    val version: Int = 1,
    val requestId: String = UUID.randomUUID().toString(),
    val command: LanRemoteCommand,
    val keyCode: Int? = null,
    val text: String? = null,
    val play: LanRemotePlayPayload? = null,
)

@Serializable
data class LanRemoteResponse(
    val version: Int = 1,
    val requestId: String,
    val accepted: Boolean,
    val message: String? = null,
)

@Serializable
data class EncryptedRemoteFrame(
    val version: Int = LanRemoteProtocol.VERSION,
    val deviceId: String,
    val nonce: String,
    val ciphertext: String,
)

object LanRemoteProtocol {
    const val VERSION = 2
    const val PORT = 46900
    const val SERVICE_TYPE = "_cloudstream-remote._tcp."
    const val MAX_FRAME_BYTES = 1024 * 1024

    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * Reads one `[4-byte big-endian length][UTF-8 JSON]` frame and returns the raw
     * JSON element. Used by the server to distinguish v1 frames ("command" key)
     * from v2 envelopes ("type" key) before decoding.
     */
    fun readFrame(input: DataInputStream, maxFrameBytes: Int = MAX_FRAME_BYTES): JsonElement {
        require(maxFrameBytes in 1..MAX_FRAME_BYTES)
        val size = input.readInt()
        require(size in 1..maxFrameBytes) { "Invalid frame size: $size" }
        val payload = ByteArray(size)
        input.readFully(payload)
        return json.parseToJsonElement(payload.decodeToString())
    }

    fun writeJson(output: DataOutputStream, element: JsonElement) {
        val payload = json.encodeToString(element).encodeToByteArray()
        require(payload.size <= MAX_FRAME_BYTES) { "Frame is too large" }
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    /** True if the frame is a v2 [RemoteEnvelope] (has a "type" field). */
    fun isV2Frame(element: JsonElement): Boolean =
        element is JsonObject && element.containsKey("type")

    fun isEncryptedFrame(element: JsonElement): Boolean =
        element is JsonObject && element.containsKey("ciphertext") && element.containsKey("nonce")

    fun writeEncrypted(
        output: DataOutputStream,
        key: ByteArray,
        deviceId: String,
        element: JsonElement,
    ) {
        val encrypted = RemoteCrypto.encrypt(key, json.encodeToString(element).encodeToByteArray())
        write(
            output,
            EncryptedRemoteFrame(
                deviceId = deviceId,
                nonce = RemoteAuth.encodeBase64(encrypted.nonce),
                ciphertext = RemoteAuth.encodeBase64(encrypted.ciphertext),
            ),
        )
    }

    fun decryptFrame(element: JsonElement, key: ByteArray): Pair<EncryptedRemoteFrame, JsonElement> {
        val frame = json.decodeFromJsonElement<EncryptedRemoteFrame>(element)
        val nonce = RemoteAuth.decodeBase64(frame.nonce) ?: error("Invalid encrypted nonce")
        val ciphertext = RemoteAuth.decodeBase64(frame.ciphertext) ?: error("Invalid encrypted frame")
        val plaintext = RemoteCrypto.decrypt(key, nonce, ciphertext) ?: error("Invalid encrypted frame")
        return frame to json.parseToJsonElement(plaintext.decodeToString())
    }

    fun readDecrypted(
        input: DataInputStream,
        key: ByteArray,
    ): Pair<EncryptedRemoteFrame, JsonElement> = decryptFrame(readFrame(input), key)

    inline fun <reified T> read(input: DataInputStream): T =
        json.decodeFromString(readFrame(input).toString())

    inline fun <reified T> write(output: DataOutputStream, value: T) {
        writeJson(output, json.parseToJsonElement(json.encodeToString(value)))
    }
}
