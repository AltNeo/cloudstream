package com.lagradost.cloudstream3.companion.crypto

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** The role used to bind handshake confirmations and map session directions. */
enum class CompanionRole(internal val wireName: String) {
    PHONE("phone"),
    TV("tv"),
}

/**
 * Canonical key ordering for the companion handshake transcript.
 *
 * Both peers construct this value with the same ordering: TV ephemeral key, phone
 * ephemeral key, TV identity key, then phone identity key. Length-prefixing each
 * encoded key prevents concatenation ambiguity.
 */
data class HandshakeTranscript(
    val tvEphemeralPublicKey: ByteArray,
    val phoneEphemeralPublicKey: ByteArray,
    val tvIdentityPublicKey: ByteArray,
    val phoneIdentityPublicKey: ByteArray,
) {
    fun canonicalBytes(): ByteArray = CompanionCrypto.canonicalTranscriptBytes(this)

    fun hash(): ByteArray = CompanionCrypto.sha256(canonicalBytes())
}

data class DirectionalKeys(
    val key: SecretKey,
    val nonceSalt: ByteArray,
) {
    init {
        require(key.encoded.size == CompanionCrypto.AES_KEY_SIZE) {
            "AES-256 requires a 32-byte key"
        }
        require(nonceSalt.size == AeadRecordLayer.NONCE_SALT_SIZE) {
            "GCM nonce salt must be four bytes"
        }
    }
}

data class SessionKeys(
    val clientToServer: DirectionalKeys,
    val serverToClient: DirectionalKeys,
) {
    /** Returns send/receive keys for the given endpoint role. */
    fun forRole(role: CompanionRole): RecordLayerKeys = when (role) {
        CompanionRole.PHONE -> RecordLayerKeys(clientToServer, serverToClient)
        CompanionRole.TV -> RecordLayerKeys(serverToClient, clientToServer)
    }
}

data class RecordLayerKeys(
    val send: DirectionalKeys,
    val receive: DirectionalKeys,
)

/** Pure JVM cryptographic operations shared by the phone and TV endpoints. */
object CompanionCrypto {
    const val AES_KEY_SIZE = 32
    const val GCM_TAG_SIZE_BITS = 128
    const val SESSION_INFO = "cs-companion-session-v3"
    const val PAIRING_INFO = "cs-companion-pair-v3"

    private const val P256 = "secp256r1"
    private const val ECDSA_ALGORITHM = "SHA256withECDSA"

    private val p256Parameters: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec(P256))
        }.getParameterSpec(ECParameterSpec::class.java)
    }

    fun generateP256KeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(P256))
        }.generateKeyPair()

    fun performEcdh(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray = try {
        requireP256(peerPublicKey)
        KeyAgreement.getInstance("ECDH").apply {
            init(privateKey)
            doPhase(peerPublicKey, true)
        }.generateSecret()
    } catch (error: GeneralSecurityException) {
        throw CryptoException("ECDH failed", error)
    }

    fun decodePublicKey(encoded: ByteArray): PublicKey = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)).also(::requireP256)
    } catch (error: GeneralSecurityException) {
        throw CryptoException("invalid P-256 public key", error)
    } catch (error: IllegalArgumentException) {
        throw CryptoException("invalid P-256 public key", error)
    }

    private fun requireP256(publicKey: PublicKey) {
        val ecKey = publicKey as? ECPublicKey
            ?: throw IllegalArgumentException("public key must use EC")
        if (!sameParameters(ecKey.params, p256Parameters) ||
            !isOnCurve(ecKey.w, ecKey.params)
        ) {
            throw IllegalArgumentException("public key must use secp256r1")
        }
    }

    private fun sameParameters(left: ECParameterSpec, right: ECParameterSpec): Boolean {
        val leftCurve = left.curve
        val rightCurve = right.curve
        val leftField = leftCurve.field as? java.security.spec.ECFieldFp ?: return false
        val rightField = rightCurve.field as? java.security.spec.ECFieldFp ?: return false
        return leftField.p == rightField.p &&
            leftCurve.a == rightCurve.a &&
            leftCurve.b == rightCurve.b &&
            left.generator == right.generator &&
            left.order == right.order &&
            left.cofactor == right.cofactor
    }

    private fun isOnCurve(point: ECPoint, parameters: ECParameterSpec): Boolean {
        if (point == ECPoint.POINT_INFINITY) return false
        val curve = parameters.curve
        val field = curve.field as? java.security.spec.ECFieldFp ?: return false
        val p = field.p
        val x = point.affineX
        val y = point.affineY
        if (x.signum() < 0 || y.signum() < 0 || x >= p || y >= p) return false
        val left = y.multiply(y).mod(p)
        val right = x.multiply(x).multiply(x)
            .add(curve.a.multiply(x))
            .add(curve.b)
            .mod(p)
        return left == right
    }

    fun canonicalTranscriptBytes(transcript: HandshakeTranscript): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            listOf(
                transcript.tvEphemeralPublicKey,
                transcript.phoneEphemeralPublicKey,
                transcript.tvIdentityPublicKey,
                transcript.phoneIdentityPublicKey,
            ).forEach { key ->
                require(key.isNotEmpty()) { "transcript keys must not be empty" }
                data.writeInt(key.size)
                data.write(key)
            }
        }
        return output.toByteArray()
    }

    fun sha256(value: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(value)

    /** Derives the PIN-bound pairing key from the ephemeral ECDH secret. */
    fun derivePairingKey(
        sessionSecret: ByteArray,
        transcriptHash: ByteArray,
        pin: String,
    ): ByteArray {
        require(pin.length == 6 && pin.all { it in '0'..'9' }) { "PIN must be six digits" }
        return Hkdf.derive(
            inputKeyMaterial = sessionSecret,
            salt = transcriptHash,
            info = PAIRING_INFO.encodeToByteArray() + pin.encodeToByteArray(),
            outputLength = AES_KEY_SIZE,
        )
    }

    fun pairingConfirmation(
        pairingKey: ByteArray,
        transcriptHash: ByteArray,
        role: CompanionRole,
    ): ByteArray = hmacSha256(
        key = pairingKey,
        value = transcriptHash + role.wireName.encodeToByteArray(),
    )

    fun verifyPairingConfirmation(
        pairingKey: ByteArray,
        transcriptHash: ByteArray,
        role: CompanionRole,
        confirmation: ByteArray,
    ): Boolean = constantTimeEquals(
        pairingConfirmation(pairingKey, transcriptHash, role),
        confirmation,
    )

    /** Derives independent AES key + nonce salt material for each wire direction. */
    fun deriveSessionKeys(sharedSecret: ByteArray, transcriptHash: ByteArray): SessionKeys =
        SessionKeys(
            clientToServer = deriveDirectionalKeys(sharedSecret, transcriptHash, "c2s"),
            serverToClient = deriveDirectionalKeys(sharedSecret, transcriptHash, "s2c"),
        )

    fun signTranscript(privateKey: PrivateKey, transcript: HandshakeTranscript): ByteArray = try {
        Signature.getInstance(ECDSA_ALGORITHM).apply {
            initSign(privateKey)
            update(transcript.canonicalBytes())
        }.sign()
    } catch (error: GeneralSecurityException) {
        throw CryptoException("transcript signing failed", error)
    }

    fun verifyTranscriptSignature(
        publicKey: PublicKey,
        transcript: HandshakeTranscript,
        signature: ByteArray,
    ): Boolean = try {
        Signature.getInstance(ECDSA_ALGORITHM).apply {
            initVerify(publicKey)
            update(transcript.canonicalBytes())
        }.verify(signature)
    } catch (_: GeneralSecurityException) {
        false
    }

    internal fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray = try {
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }.doFinal(value)
    } catch (error: GeneralSecurityException) {
        throw CryptoException("HMAC-SHA256 failed", error)
    }

    internal fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(left, right)

    private fun deriveDirectionalKeys(
        sharedSecret: ByteArray,
        transcriptHash: ByteArray,
        direction: String,
    ): DirectionalKeys {
        val material = Hkdf.derive(
            inputKeyMaterial = sharedSecret,
            salt = transcriptHash,
            info = SESSION_INFO.encodeToByteArray() + direction.encodeToByteArray(),
            outputLength = AES_KEY_SIZE + AeadRecordLayer.NONCE_SALT_SIZE,
        )
        return DirectionalKeys(
            key = SecretKeySpec(material.copyOfRange(0, AES_KEY_SIZE), "AES"),
            nonceSalt = material.copyOfRange(AES_KEY_SIZE, material.size),
        )
    }
}

internal object Hkdf {
    private const val HASH_SIZE = 32

    fun derive(
        inputKeyMaterial: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
        outputLength: Int,
    ): ByteArray {
        require(outputLength in 0..(255 * HASH_SIZE)) { "invalid HKDF output length" }
        if (outputLength == 0) return ByteArray(0)

        val pseudoRandomKey = extract(salt, inputKeyMaterial)
        val result = ByteArrayOutputStream(outputLength)
        var previous = ByteArray(0)
        var counter = 1
        while (result.size() < outputLength) {
            previous = CompanionCrypto.hmacSha256(
                key = pseudoRandomKey,
                value = previous + info + byteArrayOf(counter.toByte()),
            )
            result.write(previous)
            counter++
        }
        return result.toByteArray().copyOf(outputLength)
    }

    private fun extract(salt: ByteArray?, inputKeyMaterial: ByteArray): ByteArray =
        CompanionCrypto.hmacSha256(salt ?: ByteArray(HASH_SIZE), inputKeyMaterial)
}

class CryptoException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
