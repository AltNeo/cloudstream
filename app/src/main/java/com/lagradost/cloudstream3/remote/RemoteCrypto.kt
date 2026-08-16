package com.lagradost.cloudstream3.remote

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/** Small, provider-independent primitives used by the LAN pairing and frame transport. */
object RemoteCrypto {
    private const val AES_ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val KEY_BYTES = 32

    data class Encrypted(val nonce: ByteArray, val ciphertext: ByteArray)

    fun newPairingKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    fun publicKeyBase64(keyPair: KeyPair): String =
        RemoteAuth.encodeBase64(keyPair.public.encoded)

    fun decodePublicKey(encoded: String): PublicKey? = runCatching {
        KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(RemoteAuth.decodeBase64(encoded) ?: return null)
        )
    }.getOrNull()

    fun derivePairingKey(
        privateKey: PrivateKey,
        publicKey: PublicKey,
        pin: String,
        sessionId: String,
        phoneDeviceId: String,
    ): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        val sharedSecret = agreement.generateSecret()
        val salt = sha256("CloudStream LAN pairing v2\u0000$sessionId\u0000$phoneDeviceId".toByteArray())
        return hkdf(
            ikm = sharedSecret,
            salt = salt,
            info = "CloudStream LAN session\u0000$pin".toByteArray(),
        )
    }

    fun proof(key: ByteArray, sessionId: String, deviceId: String): String =
        RemoteAuth.encodeBase64(hmac(key, "verify\u0000$sessionId\u0000$deviceId".toByteArray()))

    fun encrypt(key: ByteArray, plaintext: ByteArray): Encrypted {
        require(key.size == KEY_BYTES) { "AES-256 key required" }
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return Encrypted(nonce, cipher.doFinal(plaintext))
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray? = runCatching {
        require(key.size == KEY_BYTES && nonce.size == NONCE_BYTES)
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.doFinal(ciphertext)
    }.getOrNull()

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(salt, ikm)
        val output = ByteArrayOutputStream(KEY_BYTES)
        var previous = ByteArray(0)
        var counter = 1
        while (output.size() < KEY_BYTES) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            output.write(previous)
            counter++
        }
        return output.toByteArray().copyOf(KEY_BYTES)
    }

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray =
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(SecretKeySpec(key, HMAC_ALGORITHM))
            doFinal(input)
        }

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)
}
