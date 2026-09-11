package com.lagradost.cloudstream3.companion.crypto

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated record layer for one established companion session.
 *
 * The wire record is `uint64 counter || AES-GCM ciphertext+tag`. The counter is
 * authenticated through the nonce and strictly increases for received records.
 * A failed record permanently closes this layer, matching the transport rule that
 * any decrypt failure terminates the connection.
 */
class AeadRecordLayer(
    private val keys: RecordLayerKeys,
    private val protocolVersion: Int = 3,
    private val maxRecordSize: Int = 1 shl 20,
) {
    init {
        require(maxRecordSize >= RECORD_OVERHEAD) {
            "maximum record size must include counter and GCM tag"
        }
        require(protocolVersion in 0..255) { "protocol version must fit in one byte" }
    }

    private var sendCounter = 0L
    private var lastReceivedCounter = -1L
    private var closed = false

    @Synchronized
    fun encrypt(plaintext: ByteArray): ByteArray {
        checkOpen()
        require(plaintext.size <= maxRecordSize - RECORD_OVERHEAD) {
            "record plaintext exceeds maximum size"
        }
        check(sendCounter >= 0L) { "send counter exhausted" }

        val counter = sendCounter++
        val ciphertext = crypt(
            mode = Cipher.ENCRYPT_MODE,
            key = keys.send.key,
            nonceSalt = keys.send.nonceSalt,
            counter = counter,
            bytes = plaintext,
        )
        return ByteBuffer.allocate(COUNTER_SIZE + ciphertext.size)
            .putLong(counter)
            .put(ciphertext)
            .array()
    }

    @Synchronized
    fun decrypt(record: ByteArray): ByteArray {
        checkOpen()
        if (record.size < MIN_RECORD_SIZE || record.size > maxRecordSize) {
            closeAndThrow("invalid encrypted record size")
        }

        val buffer = ByteBuffer.wrap(record)
        val counter = buffer.long
        if (counter < 0L || counter <= lastReceivedCounter) {
            closeAndThrow("replayed or out-of-order encrypted record")
        }

        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val plaintext = try {
            crypt(
                mode = Cipher.DECRYPT_MODE,
                key = keys.receive.key,
                nonceSalt = keys.receive.nonceSalt,
                counter = counter,
                bytes = ciphertext,
            )
        } catch (error: GeneralSecurityException) {
            closeAndThrow("encrypted record authentication failed", error)
        }

        lastReceivedCounter = counter
        return plaintext
    }

    @Synchronized
    fun isClosed(): Boolean = closed

    private fun crypt(
        mode: Int,
        key: javax.crypto.SecretKey,
        nonceSalt: ByteArray,
        counter: Long,
        bytes: ByteArray,
    ): ByteArray {
        val nonce = nonceSalt + ByteBuffer.allocate(COUNTER_SIZE).putLong(counter).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(CompanionCrypto.GCM_TAG_SIZE_BITS, nonce))
            updateAAD(byteArrayOf(protocolVersion.toByte()))
        }.doFinal(bytes)
    }

    private fun checkOpen() {
        check(!closed) { "record layer is closed" }
    }

    private fun closeAndThrow(message: String, cause: Throwable? = null): Nothing {
        closed = true
        throw CryptoException(message, cause)
    }

    companion object {
        const val NONCE_SALT_SIZE = 4
        private const val COUNTER_SIZE = Long.SIZE_BYTES
        private const val GCM_TAG_SIZE_BYTES = CompanionCrypto.GCM_TAG_SIZE_BITS / 8
        private const val RECORD_OVERHEAD = COUNTER_SIZE + GCM_TAG_SIZE_BYTES
        private const val MIN_RECORD_SIZE = RECORD_OVERHEAD
    }
}
