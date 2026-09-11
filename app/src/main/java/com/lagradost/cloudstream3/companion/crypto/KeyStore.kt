package com.lagradost.cloudstream3.companion.crypto

import java.security.KeyPair
import java.security.PublicKey

data class PairedPeer(
    val alias: String,
    val publicKey: PublicKey,
    val deviceName: String,
    val pairedAtMs: Long,
)

/** Storage boundary keeping identity keys and paired peer metadata out of protocol code. */
interface CompanionKeyStore {
    fun getOrCreateIdentity(alias: String): KeyPair

    fun savePeer(
        alias: String,
        publicKey: PublicKey,
        deviceName: String,
        pairedAtMs: Long,
    )

    fun getPeer(alias: String): PairedPeer?

    fun listPeers(): List<PairedPeer>

    fun removePeer(alias: String)
}

/** Thread-safe JVM store used by crypto and transport tests. */
class InMemoryKeyStoreImpl : CompanionKeyStore {
    private val identities = mutableMapOf<String, KeyPair>()
    private val peers = mutableMapOf<String, PairedPeer>()

    @Synchronized
    override fun getOrCreateIdentity(alias: String): KeyPair =
        identities.getOrPut(alias) { CompanionCrypto.generateP256KeyPair() }

    @Synchronized
    override fun savePeer(
        alias: String,
        publicKey: PublicKey,
        deviceName: String,
        pairedAtMs: Long,
    ) {
        peers[alias] = PairedPeer(alias, publicKey, deviceName, pairedAtMs)
    }

    @Synchronized
    override fun getPeer(alias: String): PairedPeer? = peers[alias]

    @Synchronized
    override fun listPeers(): List<PairedPeer> = peers.values.toList()

    @Synchronized
    override fun removePeer(alias: String) {
        peers.remove(alias)
    }
}
