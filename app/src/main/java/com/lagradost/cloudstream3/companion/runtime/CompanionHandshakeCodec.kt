package com.lagradost.cloudstream3.companion.runtime

import com.lagradost.cloudstream3.companion.crypto.CompanionRole
import com.lagradost.cloudstream3.companion.crypto.PairingFrame
import com.lagradost.cloudstream3.companion.crypto.PairingFrameKind
import com.lagradost.cloudstream3.companion.crypto.SessionFrame
import com.lagradost.cloudstream3.companion.crypto.SessionFrameKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Plaintext handshake codec. Application frames never use this codec. */
object CompanionHandshakeCodec {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class WireFrame(
        val channel: String,
        val kind: String,
        val role: String,
        val ephemeral: String? = null,
        val identity: String? = null,
        val deviceName: String? = null,
        val confirmation: String? = null,
        val signature: String? = null,
    )

    sealed class Decoded {
        data class Pairing(val frame: PairingFrame) : Decoded()
        data class Session(val frame: SessionFrame) : Decoded()
    }

    fun encode(frame: PairingFrame): ByteArray = encode(
        WireFrame(
            channel = PAIRING,
            kind = frame.kind.name,
            role = frame.senderRole.name,
            ephemeral = frame.ephemeralPublicKey?.toHex(),
            identity = frame.identityPublicKey?.toHex(),
            deviceName = frame.deviceName,
            confirmation = frame.confirmation?.toHex(),
        ),
    )

    fun encode(frame: SessionFrame): ByteArray = encode(
        WireFrame(
            channel = SESSION,
            kind = frame.kind.name,
            role = frame.senderRole.name,
            ephemeral = frame.ephemeralPublicKey?.toHex(),
            identity = frame.identityPublicKey?.toHex(),
            signature = frame.signature?.toHex(),
        ),
    )

    fun decode(bytes: ByteArray): Decoded {
        val frame = json.decodeFromString<WireFrame>(bytes.decodeToString())
        val role = CompanionRole.valueOf(frame.role)
        return when (frame.channel) {
            PAIRING -> Decoded.Pairing(
                PairingFrame(
                    kind = PairingFrameKind.valueOf(frame.kind),
                    senderRole = role,
                    ephemeralPublicKey = frame.ephemeral?.fromHex(),
                    identityPublicKey = frame.identity?.fromHex(),
                    deviceName = frame.deviceName,
                    confirmation = frame.confirmation?.fromHex(),
                ),
            )
            SESSION -> Decoded.Session(
                SessionFrame(
                    kind = SessionFrameKind.valueOf(frame.kind),
                    senderRole = role,
                    ephemeralPublicKey = frame.ephemeral?.fromHex(),
                    identityPublicKey = frame.identity?.fromHex(),
                    signature = frame.signature?.fromHex(),
                ),
            )
            else -> error("unknown companion handshake channel")
        }
    }

    private fun encode(frame: WireFrame): ByteArray =
        json.encodeToString(WireFrame.serializer(), frame).encodeToByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray {
        require(length % 2 == 0) { "invalid handshake byte encoding" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private const val PAIRING = "pairing"
    private const val SESSION = "session"
}
