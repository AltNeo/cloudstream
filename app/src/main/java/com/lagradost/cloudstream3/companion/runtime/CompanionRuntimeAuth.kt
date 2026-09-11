package com.lagradost.cloudstream3.companion.runtime

import com.lagradost.cloudstream3.companion.crypto.CompanionKeyStore
import com.lagradost.cloudstream3.companion.crypto.CompanionRole
import com.lagradost.cloudstream3.companion.crypto.EstablishedSession
import com.lagradost.cloudstream3.companion.crypto.PairingFrame
import com.lagradost.cloudstream3.companion.crypto.PairingHandshakeCoordinator
import com.lagradost.cloudstream3.companion.crypto.PairingHandshakeResult
import com.lagradost.cloudstream3.companion.crypto.PairingRejectReason
import com.lagradost.cloudstream3.companion.crypto.PairingWindow
import com.lagradost.cloudstream3.companion.crypto.SessionFrame
import com.lagradost.cloudstream3.companion.crypto.SessionHandshakeCoordinator
import com.lagradost.cloudstream3.companion.crypto.SessionHandshakeResult
import com.lagradost.cloudstream3.companion.crypto.CompanionCrypto
import com.lagradost.cloudstream3.companion.transport.CompanionAuthenticator
import com.lagradost.cloudstream3.companion.transport.CompanionConnection
import com.lagradost.cloudstream3.companion.protocol.FrameCodec
import java.net.Socket
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

internal data class AuthenticatedState(
    val session: EstablishedSession,
)

internal class TvCompanionAuthenticator(
    private val keyStore: CompanionKeyStore,
    private val deviceName: () -> String,
    private val pairingWindow: PairingWindow,
    private val onPendingApproval: (String, String, Long) -> Unit = { _, _, _ -> },
    private val onApprovalCleared: () -> Unit = {},
) : CompanionAuthenticator {
    private val established = ConcurrentHashMap<String, ConcurrentLinkedQueue<AuthenticatedState>>()
    private val establishedLock = Any()
    private val pendingLock = Any()
    private var pending: PendingApproval? = null

    private data class PendingApproval(
        val coordinator: PairingHandshakeCoordinator,
        val peerAlias: String,
        val decision: CompletableDeferred<Boolean>,
        val expiresAtMs: Long,
    )

    override suspend fun authenticate(socket: Socket, deadlineMs: Int): String {
        return when (val decoded = read(socket)) {
            is CompanionHandshakeCodec.Decoded.Pairing -> pair(socket, decoded.frame, deadlineMs)
            is CompanionHandshakeCodec.Decoded.Session -> session(socket, decoded.frame)
        }
    }

    /** Drops authenticated handshakes that completed before the peer was revoked. */
    fun revoke(alias: String) {
        synchronized(establishedLock) {
            keyStore.removePeer(alias)
            established.remove(alias)?.clear()
        }
    }

    fun take(alias: String): AuthenticatedState? {
        return synchronized(establishedLock) {
            if (keyStore.getPeer(alias) == null) {
                established.remove(alias)?.clear()
                return@synchronized null
            }
            established[alias]?.poll()
        }
    }

    fun approvePending() {
        synchronized(pendingLock) { pending?.decision?.complete(true) }
    }

    fun rejectPending() {
        synchronized(pendingLock) { pending?.decision?.complete(false) }
    }

    private suspend fun pair(socket: Socket, hello: PairingFrame, deadlineMs: Int): String {
        val coordinator = PairingHandshakeCoordinator(
            role = CompanionRole.TV,
            identityAlias = TV_IDENTITY_ALIAS,
            deviceName = deviceName(),
            keyStore = keyStore,
            pairingWindow = pairingWindow,
            approvalRequired = true,
        )
        val source = socket.inetAddress?.hostAddress ?: "unknown"
        val response = coordinator.accept(hello, source)
        val responseFrame = (response as? PairingHandshakeResult.Send)?.frame
            ?: reject("pairing hello rejected: $response")
        write(socket, responseFrame)
        val confirmation = read(socket)
        val result = confirmation as? CompanionHandshakeCodec.Decoded.Pairing
            ?: reject("pairing confirmation used the wrong channel")
        val pairingResult = coordinator.accept(result.frame, source)
        val pendingApproval = pairingResult as? PairingHandshakeResult.PendingApproval
            ?: reject("pairing confirmation rejected: $pairingResult")
        val decision = CompletableDeferred<Boolean>()
        synchronized(pendingLock) {
            check(pending == null) { "another phone is awaiting pairing approval" }
            pending = PendingApproval(
                coordinator = coordinator,
                peerAlias = pendingApproval.peer.alias,
                decision = decision,
                expiresAtMs = pendingApproval.expiresAtMs,
            )
        }
        onPendingApproval(
            pendingApproval.peer.alias,
            pendingApproval.peer.deviceName,
            pendingApproval.expiresAtMs,
        )
        val remainingMs = (pendingApproval.expiresAtMs - System.currentTimeMillis())
            .coerceAtLeast(1L)
        // The approval deadline is also the socket deadline. This prevents a pending approval
        // from outliving the transport's handshake timeout when a custom window is used.
        socket.soTimeout = max(deadlineMs, remainingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val approved = try {
            withTimeoutOrNull(remainingMs) { decision.await() } == true
        } finally {
            synchronized(pendingLock) {
                if (pending?.decision === decision) pending = null
            }
            onApprovalCleared()
        }
        val established = if (approved) {
            pendingApproval.let {
                coordinator.approvePending() as? PairingHandshakeResult.SendAndEstablished
            }
        } else {
            coordinator.rejectPending()
            null
        }
        val finalFrame = established?.frame
            ?: reject("pairing approval rejected, timed out, or could not be completed")
        write(socket, finalFrame)
        return established.peer.alias
    }

    private fun session(socket: Socket, hello: SessionFrame): String {
        val peerAlias = fingerprint(hello.identityPublicKey)
        val coordinator = SessionHandshakeCoordinator(
            role = CompanionRole.TV,
            initiator = false,
            identityAlias = TV_IDENTITY_ALIAS,
            peerAlias = peerAlias,
            keyStore = keyStore,
        )
        val response = coordinator.accept(hello)
        val responseFrame = (response as? SessionHandshakeResult.Send)?.frame
            ?: reject("session hello rejected: $response")
        write(socket, responseFrame)
        val confirmation = read(socket)
        val result = confirmation as? CompanionHandshakeCodec.Decoded.Session
            ?: reject("session confirmation used the wrong channel")
        val established = coordinator.accept(result.frame)
        val final = (established as? SessionHandshakeResult.SendAndEstablished)
            ?: reject("session confirmation rejected: $established")
        write(socket, final.frame)
        synchronized(establishedLock) {
            if (keyStore.getPeer(final.session.peer.alias) != null) {
                this.established.getOrPut(final.session.peer.alias) { ConcurrentLinkedQueue() }
                    .add(AuthenticatedState(final.session))
            }
        }
        return final.session.peer.alias
    }

    private fun read(socket: Socket): CompanionHandshakeCodec.Decoded =
        CompanionHandshakeCodec.decode(FrameCodec.readFrame(socket.getInputStream()))

    private fun write(socket: Socket, frame: PairingFrame) =
        FrameCodec.writeFrame(socket.getOutputStream(), CompanionHandshakeCodec.encode(frame))

    private fun write(socket: Socket, frame: SessionFrame) =
        FrameCodec.writeFrame(socket.getOutputStream(), CompanionHandshakeCodec.encode(frame))

    private fun reject(message: String): Nothing = error(message)

    companion object {
        const val TV_IDENTITY_ALIAS = "tv-identity"
    }
}

internal class PhonePairingAuthenticator(
    keyStore: CompanionKeyStore,
    deviceName: String,
    pin: String,
) : CompanionAuthenticator {
    private val coordinator = PairingHandshakeCoordinator(
        role = CompanionRole.PHONE,
        identityAlias = PHONE_IDENTITY_ALIAS,
        deviceName = deviceName,
        keyStore = keyStore,
    ).also { it.setPairingPin(pin) }

    var peerAlias: String? = null
        private set

    override suspend fun authenticate(socket: Socket, deadlineMs: Int): String {
        write(socket, coordinator.start())
        val responsePayload = (read(socket) as? CompanionHandshakeCodec.Decoded.Pairing)?.frame
            ?: error("pairing response used the wrong channel")
        val response = coordinator.accept(responsePayload)
        val responseFrame = (response as? PairingHandshakeResult.Send)?.frame
            ?: error("pairing response rejected: $response")
        write(socket, responseFrame)
        val final = read(socket)
        val result = final as? CompanionHandshakeCodec.Decoded.Pairing
            ?: error("pairing confirmation used the wrong channel")
        val established = coordinator.accept(result.frame)
            as? PairingHandshakeResult.Established
            ?: error("pairing confirmation rejected")
        peerAlias = established.peer.alias
        return peerAlias!!
    }

    private fun read(socket: Socket): CompanionHandshakeCodec.Decoded =
        CompanionHandshakeCodec.decode(FrameCodec.readFrame(socket.getInputStream()))

    private fun write(socket: Socket, frame: PairingFrame) =
        FrameCodec.writeFrame(socket.getOutputStream(), CompanionHandshakeCodec.encode(frame))
}

internal class PhoneSessionAuthenticator(
    private val keyStore: CompanionKeyStore,
    private val peerAlias: String,
) : CompanionAuthenticator {
    var established: EstablishedSession? = null
        private set

    override suspend fun authenticate(socket: Socket, deadlineMs: Int): String {
        val coordinator = SessionHandshakeCoordinator(
            role = CompanionRole.PHONE,
            initiator = true,
            identityAlias = PHONE_IDENTITY_ALIAS,
            peerAlias = peerAlias,
            keyStore = keyStore,
        )
        write(socket, coordinator.start())
        val response = read(socket) as? CompanionHandshakeCodec.Decoded.Session
            ?: error("session response used the wrong channel")
        val confirmation = coordinator.accept(response.frame)
            as? SessionHandshakeResult.Send
            ?: error("session response rejected")
        write(socket, confirmation.frame)
        val ack = read(socket) as? CompanionHandshakeCodec.Decoded.Session
            ?: error("session ack used the wrong channel")
        val result = coordinator.accept(ack.frame)
            as? SessionHandshakeResult.Established
            ?: error("session ack rejected")
        established = result.session
        return result.session.peer.alias
    }

    private fun read(socket: Socket): CompanionHandshakeCodec.Decoded =
        CompanionHandshakeCodec.decode(FrameCodec.readFrame(socket.getInputStream()))

    private fun write(socket: Socket, frame: SessionFrame) =
        FrameCodec.writeFrame(socket.getOutputStream(), CompanionHandshakeCodec.encode(frame))
}

internal fun fingerprint(encodedPublicKey: ByteArray?): String {
    require(encodedPublicKey != null) { "handshake identity key is missing" }
    return CompanionCrypto.sha256(encodedPublicKey).joinToString("") { "%02x".format(it) }
}

internal const val PHONE_IDENTITY_ALIAS = "phone-identity"
