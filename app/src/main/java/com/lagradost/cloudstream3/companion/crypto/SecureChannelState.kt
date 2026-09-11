package com.lagradost.cloudstream3.companion.crypto

enum class ChannelPhase {
    HANDSHAKING,
    ESTABLISHED,
    CLOSED,
}

class ChannelStateException(message: String) : IllegalStateException(message)

/**
 * Small transport adapter that makes the plaintext/encrypted boundary explicit.
 * Handshake frames are accepted only before establishment; application payloads
 * must be encrypted records after establishment.
 */
class SecureChannelState {
    private val lock = Any()
    private var phase = ChannelPhase.HANDSHAKING
    private var recordLayer: AeadRecordLayer? = null

    fun phase(): ChannelPhase = synchronized(lock) { phase }

    fun acceptHandshakeFrame() = synchronized(lock) {
        ensurePhase(ChannelPhase.HANDSHAKING)
    }

    fun establish(layer: AeadRecordLayer) = synchronized(lock) {
        ensurePhase(ChannelPhase.HANDSHAKING)
        recordLayer = layer
        phase = ChannelPhase.ESTABLISHED
    }

    fun encryptApplicationRecord(plaintext: ByteArray): ByteArray = synchronized(lock) {
        ensurePhase(ChannelPhase.ESTABLISHED)
        try {
            recordLayer!!.encrypt(plaintext)
        } catch (error: Exception) {
            phase = ChannelPhase.CLOSED
            throw error
        }
    }

    fun decryptApplicationRecord(record: ByteArray): ByteArray = synchronized(lock) {
        ensurePhase(ChannelPhase.ESTABLISHED)
        try {
            recordLayer!!.decrypt(record)
        } catch (error: Exception) {
            phase = ChannelPhase.CLOSED
            throw error
        }
    }

    fun rejectPlaintextApplicationFrame(): Nothing = synchronized(lock) {
        phase = ChannelPhase.CLOSED
        throw ChannelStateException("plaintext application frame is not allowed")
    }

    fun close() = synchronized(lock) {
        phase = ChannelPhase.CLOSED
        recordLayer = null
    }

    private fun ensurePhase(expected: ChannelPhase) {
        if (phase != expected) {
            phase = ChannelPhase.CLOSED
            recordLayer = null
            throw ChannelStateException("channel is $phase, expected $expected")
        }
    }
}
