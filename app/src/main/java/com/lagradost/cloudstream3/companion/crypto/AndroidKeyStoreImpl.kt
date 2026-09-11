package com.lagradost.cloudstream3.companion.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Android identity storage. Private identity keys remain in Android Keystore;
 * paired public keys and display metadata are kept in a dedicated preferences file.
 */
class AndroidKeyStoreImpl(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : CompanionKeyStore {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val lock = Any()

    private val keyStore: java.security.KeyStore by lazy {
        java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    override fun getOrCreateIdentity(alias: String): KeyPair = synchronized(lock) {
        val existing = readIdentity(alias)
        if (existing != null) return existing

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_AGREE_KEY,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec(P256))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
        }.generateKeyPair().also {
            // Force the entry to be readable before returning it. This catches a
            // provider failure while the identity is still being initialized.
            check(readIdentity(alias) != null) { "Android Keystore identity was not persisted" }
        }
    }

    override fun savePeer(
        alias: String,
        publicKey: PublicKey,
        deviceName: String,
        pairedAtMs: Long,
    ) = synchronized(lock) {
        preferences.edit()
            .putString(peerKey(alias, PUBLIC_KEY_SUFFIX), encode(publicKey.encoded))
            .putString(peerKey(alias, DEVICE_NAME_SUFFIX), deviceName)
            .putLong(peerKey(alias, PAIRED_AT_SUFFIX), pairedAtMs)
            .apply()
    }

    override fun getPeer(alias: String): PairedPeer? = synchronized(lock) {
        val encoded = preferences.getString(peerKey(alias, PUBLIC_KEY_SUFFIX), null)
            ?.let(::decode)
            ?: return@synchronized null
        val publicKey = runCatching { CompanionCrypto.decodePublicKey(encoded) }.getOrNull()
            ?: return@synchronized null
        PairedPeer(
            alias = alias,
            publicKey = publicKey,
            deviceName = preferences.getString(peerKey(alias, DEVICE_NAME_SUFFIX), "") ?: "",
            pairedAtMs = preferences.getLong(peerKey(alias, PAIRED_AT_SUFFIX), 0L),
        )
    }

    override fun listPeers(): List<PairedPeer> = synchronized(lock) {
        preferences.all.keys
            .asSequence()
            .filter { it.startsWith("peer.") && it.endsWith(".$PUBLIC_KEY_SUFFIX") }
            .mapNotNull { key -> getPeer(key.removePrefix("peer.").removeSuffix(".$PUBLIC_KEY_SUFFIX")) }
            .toList()
    }

    override fun removePeer(alias: String) = synchronized(lock) {
        preferences.edit()
            .remove(peerKey(alias, PUBLIC_KEY_SUFFIX))
            .remove(peerKey(alias, DEVICE_NAME_SUFFIX))
            .remove(peerKey(alias, PAIRED_AT_SUFFIX))
            .apply()
    }

    private fun readIdentity(alias: String): KeyPair? {
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey ?: return null
        val publicKey = keyStore.getCertificate(alias)?.publicKey ?: return null
        return KeyPair(publicKey, privateKey)
    }

    private fun peerKey(alias: String, suffix: String): String = "peer.$alias.$suffix"

    private fun encode(value: ByteArray): String =
        Base64.encodeToString(value, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray? =
        runCatching { Base64.decode(value, Base64.NO_WRAP) }.getOrNull()

    companion object {
        const val DEFAULT_PREFERENCES_NAME = "companion_keys"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val P256 = "secp256r1"
        private const val PUBLIC_KEY_SUFFIX = "public_key"
        private const val DEVICE_NAME_SUFFIX = "device_name"
        private const val PAIRED_AT_SUFFIX = "paired_at"
    }
}
