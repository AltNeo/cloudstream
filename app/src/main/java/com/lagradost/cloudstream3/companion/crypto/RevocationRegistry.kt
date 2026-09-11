package com.lagradost.cloudstream3.companion.crypto

/**
 * Transport-facing revocation hook. Each live connection registers its peer
 * alias; revocation removes the stored public key and synchronously notifies all
 * registered connections so event channels cannot survive unpairing.
 */
class RevocationRegistry(
    private val keyStore: CompanionKeyStore,
) {
    private val lock = Any()
    private val listeners = mutableMapOf<String, MutableSet<Registration>>()

    fun register(peerAlias: String, onRevoked: () -> Unit): Registration = synchronized(lock) {
        Registration(peerAlias, onRevoked).also {
            listeners.getOrPut(peerAlias) { mutableSetOf() }.add(it)
        }
    }

    fun revoke(peerAlias: String): Boolean {
        val (hadPeer, pending) = synchronized(lock) {
            val hadPeer = keyStore.getPeer(peerAlias) != null
            keyStore.removePeer(peerAlias)
            val registered = listeners.remove(peerAlias)?.toList().orEmpty()
            hadPeer to registered
        }
        pending.forEach { it.notifyRevoked() }
        return hadPeer || pending.isNotEmpty()
    }

    inner class Registration internal constructor(
        private val peerAlias: String,
        private val onRevoked: () -> Unit,
    ) : AutoCloseable {
        private var closed = false

        internal fun notifyRevoked() = onRevoked()

        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                listeners[peerAlias]?.let { peers ->
                    peers.remove(this)
                    if (peers.isEmpty()) listeners.remove(peerAlias)
                }
            }
        }
    }
}
