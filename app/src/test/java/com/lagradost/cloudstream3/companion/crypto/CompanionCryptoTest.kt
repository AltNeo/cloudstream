package com.lagradost.cloudstream3.companion.crypto

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Arrays
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class CompanionCryptoTest {
    @Test
    fun `p256 peers derive the same ecdh secret`() {
        val phone = CompanionCrypto.generateP256KeyPair()
        val tv = CompanionCrypto.generateP256KeyPair()

        assertArrayEquals(
            CompanionCrypto.performEcdh(phone.getPrivate(), tv.getPublic()),
            CompanionCrypto.performEcdh(tv.getPrivate(), phone.getPublic()),
        )
    }

    @Test
    fun `decoded keys must use p256`() {
        val p384 = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp384r1"))
        }.generateKeyPair()

        assertThrows(CryptoException::class.java) {
            CompanionCrypto.decodePublicKey(p384.public.encoded)
        }
    }

    @Test
    fun `pairing confirmation binds pin transcript and role`() {
        val phoneEphemeral = CompanionCrypto.generateP256KeyPair()
        val tvEphemeral = CompanionCrypto.generateP256KeyPair()
        val phoneIdentity = CompanionCrypto.generateP256KeyPair()
        val tvIdentity = CompanionCrypto.generateP256KeyPair()
        val transcript = transcript(phoneEphemeral, tvEphemeral, phoneIdentity, tvIdentity)
        val secret = CompanionCrypto.performEcdh(
            phoneEphemeral.getPrivate(),
            tvEphemeral.getPublic(),
        )
        val key = CompanionCrypto.derivePairingKey(secret, transcript.hash(), "123456")
        val confirmation = CompanionCrypto.pairingConfirmation(
            key,
            transcript.hash(),
            CompanionRole.PHONE,
        )

        assertTrue(
            CompanionCrypto.verifyPairingConfirmation(
                key,
                transcript.hash(),
                CompanionRole.PHONE,
                confirmation,
            ),
        )
        assertFalse(
            CompanionCrypto.verifyPairingConfirmation(
                CompanionCrypto.derivePairingKey(secret, transcript.hash(), "654321"),
                transcript.hash(),
                CompanionRole.PHONE,
                confirmation,
            ),
        )
        assertFalse(
            CompanionCrypto.verifyPairingConfirmation(
                key,
                transcript.hash(),
                CompanionRole.TV,
                confirmation,
            ),
        )
    }

    @Test
    fun `session directions are independent and map by role`() {
        val transcript = transcript(
            CompanionCrypto.generateP256KeyPair(),
            CompanionCrypto.generateP256KeyPair(),
            CompanionCrypto.generateP256KeyPair(),
            CompanionCrypto.generateP256KeyPair(),
        )
        val sharedSecret = ByteArray(32) { it.toByte() }
        val keys = CompanionCrypto.deriveSessionKeys(sharedSecret, transcript.hash())
        val phone = keys.forRole(CompanionRole.PHONE)
        val tv = keys.forRole(CompanionRole.TV)

        assertArrayEquals(phone.send.key.encoded, tv.receive.key.encoded)
        assertArrayEquals(phone.receive.key.encoded, tv.send.key.encoded)
        assertFalse(Arrays.equals(phone.send.key.encoded, phone.receive.key.encoded))
        assertFalse(Arrays.equals(phone.send.nonceSalt, phone.receive.nonceSalt))
    }

    @Test
    fun `signatures reject a tampered transcript`() {
        val identity = CompanionCrypto.generateP256KeyPair()
        val transcript = transcriptWithBytes(1)
        val signature = CompanionCrypto.signTranscript(identity.getPrivate(), transcript)
        val tampered = transcript.copy(phoneIdentityPublicKey = byteArrayOf(9, 8, 7))

        assertTrue(
            CompanionCrypto.verifyTranscriptSignature(identity.getPublic(), transcript, signature),
        )
        assertFalse(
            CompanionCrypto.verifyTranscriptSignature(identity.getPublic(), tampered, signature),
        )
    }

    @Test
    fun `signature from an unknown identity is rejected`() {
        val identity = CompanionCrypto.generateP256KeyPair()
        val unknownIdentity = CompanionCrypto.generateP256KeyPair()
        val transcript = transcriptWithBytes(4)
        val signature = CompanionCrypto.signTranscript(unknownIdentity.getPrivate(), transcript)

        assertFalse(
            CompanionCrypto.verifyTranscriptSignature(identity.getPublic(), transcript, signature),
        )
    }

    @Test
    fun `record layer round trips and authenticates aad`() {
        val keys = testRecordKeys()
        val sender = AeadRecordLayer(keys)
        val receiver = AeadRecordLayer(testRecordKeys(CompanionRole.TV))
        val payload = "hello companion".encodeToByteArray()

        val record = sender.encrypt(payload)
        assertArrayEquals(payload, receiver.decrypt(record))
        assertTrue(record.size > payload.size)
    }

    @Test
    fun `replay and out of order records permanently close receiver`() {
        val keys = testRecordKeys()
        val sender = AeadRecordLayer(keys)
        val receiver = AeadRecordLayer(testRecordKeys(CompanionRole.TV))
        val first = sender.encrypt(byteArrayOf(1))
        val second = sender.encrypt(byteArrayOf(2))

        receiver.decrypt(first)
        assertThrows(CryptoException::class.java) { receiver.decrypt(first) }
        assertTrue(receiver.isClosed())
        assertThrows(IllegalStateException::class.java) { receiver.decrypt(second) }

        val reorderedReceiver = AeadRecordLayer(testRecordKeys(CompanionRole.TV))
        reorderedReceiver.decrypt(second)
        assertThrows(CryptoException::class.java) { reorderedReceiver.decrypt(first) }
    }

    @Test
    fun `tampering counter or ciphertext is rejected`() {
        val keys = testRecordKeys()
        val sender = AeadRecordLayer(keys)
        val receiver = AeadRecordLayer(keys)
        val record = sender.encrypt(byteArrayOf(1, 2, 3))

        record[record.lastIndex] = (record.last() + 1).toByte()
        assertThrows(CryptoException::class.java) { receiver.decrypt(record) }
        assertTrue(receiver.isClosed())
    }

    @Test
    fun `record layer enforces maximum record size`() {
        val keys = testRecordKeys()
        val sender = AeadRecordLayer(keys, maxRecordSize = 32)

        assertThrows(IllegalArgumentException::class.java) {
            sender.encrypt(ByteArray(32))
        }
    }

    @Test
    fun `garbage records fail as crypto errors`() {
        val garbage = listOf(
            byteArrayOf(),
            byteArrayOf(1, 2, 3),
            ByteArray(8),
            ByteArray(24) { it.toByte() },
        )

        garbage.forEach { record ->
            val receiver = AeadRecordLayer(testRecordKeys())
            assertThrows(CryptoException::class.java) { receiver.decrypt(record) }
            assertTrue(receiver.isClosed())
        }
    }

    @Test
    fun `in memory key store keeps identities and peers`() {
        val store = InMemoryKeyStoreImpl()
        val identity = store.getOrCreateIdentity("phone")
        val sameIdentity = store.getOrCreateIdentity("phone")
        val peer = CompanionCrypto.generateP256KeyPair()

        assertArrayEquals(identity.getPublic().encoded, sameIdentity.getPublic().encoded)
        assertNotEquals(identity.getPublic(), peer.getPublic())
        assertTrue(store.getPeer("tv") == null)

        store.savePeer("tv", peer.getPublic(), "Living room", 123L)
        val restored = store.getPeer("tv")
        assertTrue(restored != null)
        assertArrayEquals(peer.getPublic().encoded, restored!!.publicKey.encoded)
        assertTrue(restored.deviceName == "Living room")
        assertTrue(restored.pairedAtMs == 123L)

        store.removePeer("tv")
        assertTrue(store.getPeer("tv") == null)
    }

    private fun testRecordKeys(role: CompanionRole = CompanionRole.PHONE): RecordLayerKeys {
        val session = CompanionCrypto.deriveSessionKeys(
            sharedSecret = ByteArray(32) { (it + 1).toByte() },
            transcriptHash = ByteArray(32) { (it + 7).toByte() },
        )
        return session.forRole(role)
    }

    private fun transcript(
        phoneEphemeral: KeyPair,
        tvEphemeral: KeyPair,
        phoneIdentity: KeyPair,
        tvIdentity: KeyPair,
    ): HandshakeTranscript = HandshakeTranscript(
        tvEphemeralPublicKey = tvEphemeral.getPublic().encoded,
        phoneEphemeralPublicKey = phoneEphemeral.getPublic().encoded,
        tvIdentityPublicKey = tvIdentity.getPublic().encoded,
        phoneIdentityPublicKey = phoneIdentity.getPublic().encoded,
    )

    private fun transcriptWithBytes(seed: Int): HandshakeTranscript = HandshakeTranscript(
        tvEphemeralPublicKey = byteArrayOf(seed.toByte(), 1),
        phoneEphemeralPublicKey = byteArrayOf(seed.toByte(), 2),
        tvIdentityPublicKey = byteArrayOf(seed.toByte(), 3),
        phoneIdentityPublicKey = byteArrayOf(seed.toByte(), 4),
    )
}
