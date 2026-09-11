package com.lagradost.cloudstream3.companion.crypto

import java.security.KeyPair
import java.security.PublicKey

enum class SessionFrameKind {
    HELLO,
    RESPONSE,
    CONFIRMATION,
    ACK,
}

data class SessionFrame(
    val kind: SessionFrameKind,
    val senderRole: CompanionRole,
    val ephemeralPublicKey: ByteArray? = null,
    val identityPublicKey: ByteArray? = null,
    val signature: ByteArray? = null,
)

sealed class SessionHandshakeResult {
    data class Send(val frame: SessionFrame) : SessionHandshakeResult()

    data class SendAndEstablished(
        val frame: SessionFrame,
        val session: EstablishedSession,
    ) : SessionHandshakeResult()

    data class Established(val session: EstablishedSession) : SessionHandshakeResult()

    data class Rejected(val reason: SessionRejectReason) : SessionHandshakeResult()
}

data class EstablishedSession(
    val peer: PairedPeer,
    val recordLayer: AeadRecordLayer,
)

enum class SessionRejectReason {
    INVALID_STATE,
    INVALID_FRAME,
    UNKNOWN_PEER,
    IDENTITY_MISMATCH,
    INVALID_SIGNATURE,
}

/**
 * Four-message SIGMA-style session handshake. The phone normally starts the
 * connection, but [initiator] keeps the state machine useful for either role.
 */
class SessionHandshakeCoordinator(
    private val role: CompanionRole,
    private val initiator: Boolean,
    identityAlias: String,
    private val peerAlias: String,
    private val keyStore: CompanionKeyStore,
) {
    private val identity = keyStore.getOrCreateIdentity(identityAlias)
    private var state = if (initiator) State.IDLE else State.WAITING_FOR_HELLO
    private var localEphemeral: KeyPair? = null
    private var remoteEphemeral: PublicKey? = null
    private var remoteIdentity: PublicKey? = null
    private var transcript: HandshakeTranscript? = null
    private var responderSignature: ByteArray? = null
    private var sessionKeys: SessionKeys? = null

    fun start(): SessionFrame {
        check(initiator) { "only the initiator sends the session hello" }
        check(state == State.IDLE) { "session handshake is already in progress" }
        localEphemeral = CompanionCrypto.generateP256KeyPair()
        state = State.WAITING_FOR_RESPONSE
        return SessionFrame(
            kind = SessionFrameKind.HELLO,
            senderRole = role,
            ephemeralPublicKey = localEphemeral!!.getPublic().encoded,
            identityPublicKey = identity.getPublic().encoded,
        )
    }

    fun accept(frame: SessionFrame): SessionHandshakeResult = when {
        state == State.WAITING_FOR_HELLO -> acceptHello(frame)
        state == State.WAITING_FOR_RESPONSE -> acceptResponse(frame)
        state == State.WAITING_FOR_CONFIRMATION -> acceptConfirmation(frame)
        state == State.WAITING_FOR_ACK -> acceptAck(frame)
        else -> SessionHandshakeResult.Rejected(SessionRejectReason.INVALID_STATE)
    }

    fun isEstablished(): Boolean = state == State.ESTABLISHED

    private fun acceptHello(frame: SessionFrame): SessionHandshakeResult {
        if (initiator || frame.kind != SessionFrameKind.HELLO || frame.senderRole == role) {
            return rejected(SessionRejectReason.INVALID_FRAME)
        }
        val remote = decodeKeys(frame) ?: return rejected(SessionRejectReason.INVALID_FRAME)
        if (!matchesStoredPeer(remote.first)) {
            return rejected(if (keyStore.getPeer(peerAlias) == null) {
                SessionRejectReason.UNKNOWN_PEER
            } else {
                SessionRejectReason.IDENTITY_MISMATCH
            })
        }

        localEphemeral = CompanionCrypto.generateP256KeyPair()
        remoteIdentity = remote.first
        remoteEphemeral = remote.second
        transcript = buildTranscript()
        val signature = CompanionCrypto.signTranscript(identity.getPrivate(), transcript!!)
        sessionKeys = deriveSessionKeys()
        state = State.WAITING_FOR_CONFIRMATION
        responderSignature = signature
        return SessionHandshakeResult.Send(
            SessionFrame(
                kind = SessionFrameKind.RESPONSE,
                senderRole = role,
                ephemeralPublicKey = localEphemeral!!.getPublic().encoded,
                identityPublicKey = identity.getPublic().encoded,
                signature = signature,
            ),
        )
    }

    private fun acceptResponse(frame: SessionFrame): SessionHandshakeResult {
        if (!initiator || frame.kind != SessionFrameKind.RESPONSE || frame.senderRole == role) {
            return rejected(SessionRejectReason.INVALID_FRAME)
        }
        val remote = decodeKeys(frame) ?: return rejected(SessionRejectReason.INVALID_FRAME)
        val signature = frame.signature ?: return rejected(SessionRejectReason.INVALID_FRAME)
        if (!matchesStoredPeer(remote.first)) {
            return rejected(if (keyStore.getPeer(peerAlias) == null) {
                SessionRejectReason.UNKNOWN_PEER
            } else {
                SessionRejectReason.IDENTITY_MISMATCH
            })
        }
        remoteIdentity = remote.first
        remoteEphemeral = remote.second
        transcript = buildTranscript()
        if (!CompanionCrypto.verifyTranscriptSignature(remote.first, transcript!!, signature)) {
            return rejected(SessionRejectReason.INVALID_SIGNATURE)
        }
        responderSignature = signature
        sessionKeys = deriveSessionKeys()
        state = State.WAITING_FOR_ACK
        return SessionHandshakeResult.Send(
            SessionFrame(
                kind = SessionFrameKind.CONFIRMATION,
                senderRole = role,
                signature = CompanionCrypto.signTranscript(identity.getPrivate(), transcript!!),
            ),
        )
    }

    private fun acceptConfirmation(frame: SessionFrame): SessionHandshakeResult {
        if (initiator || frame.kind != SessionFrameKind.CONFIRMATION || frame.senderRole == role) {
            return rejected(SessionRejectReason.INVALID_FRAME)
        }
        val signature = frame.signature ?: return rejected(SessionRejectReason.INVALID_FRAME)
        val remote = remoteIdentity ?: return rejected(SessionRejectReason.INVALID_STATE)
        val currentTranscript = transcript ?: return rejected(SessionRejectReason.INVALID_STATE)
        if (!CompanionCrypto.verifyTranscriptSignature(remote, currentTranscript, signature)) {
            return rejected(SessionRejectReason.INVALID_SIGNATURE)
        }
        state = State.ESTABLISHED
        return SessionHandshakeResult.SendAndEstablished(
            frame = SessionFrame(
                kind = SessionFrameKind.ACK,
                senderRole = role,
                signature = responderSignature,
            ),
            session = establishedSession(),
        )
    }

    private fun acceptAck(frame: SessionFrame): SessionHandshakeResult {
        if (!initiator || frame.kind != SessionFrameKind.ACK || frame.senderRole == role) {
            return rejected(SessionRejectReason.INVALID_FRAME)
        }
        val signature = frame.signature ?: return rejected(SessionRejectReason.INVALID_FRAME)
        val remote = remoteIdentity ?: return rejected(SessionRejectReason.INVALID_STATE)
        val currentTranscript = transcript ?: return rejected(SessionRejectReason.INVALID_STATE)
        if (!CompanionCrypto.verifyTranscriptSignature(remote, currentTranscript, signature) ||
            !CompanionCrypto.constantTimeEquals(signature, responderSignature ?: ByteArray(0))
        ) {
            return rejected(SessionRejectReason.INVALID_SIGNATURE)
        }
        state = State.ESTABLISHED
        return SessionHandshakeResult.Established(establishedSession())
    }

    private fun establishedSession(): EstablishedSession {
        val remote = remoteIdentity ?: error("missing peer identity")
        val peer = keyStore.getPeer(peerAlias) ?: error("missing paired peer")
        return EstablishedSession(
            peer = peer.copy(publicKey = remote),
            recordLayer = AeadRecordLayer(sessionKeys!!.forRole(role)),
        )
    }

    private fun deriveSessionKeys(): SessionKeys = CompanionCrypto.deriveSessionKeys(
        sharedSecret = CompanionCrypto.performEcdh(
            localEphemeral!!.getPrivate(),
            remoteEphemeral!!,
        ),
        transcriptHash = transcript!!.hash(),
    )

    private fun buildTranscript(): HandshakeTranscript {
        val local = localEphemeral ?: error("missing local ephemeral key")
        val remote = remoteEphemeral ?: error("missing peer ephemeral key")
        val localIdentity = identity.getPublic().encoded
        val peerIdentity = remoteIdentity?.encoded ?: error("missing peer identity key")
        return if (role == CompanionRole.TV) {
            HandshakeTranscript(
                tvEphemeralPublicKey = local.getPublic().encoded,
                phoneEphemeralPublicKey = remote.encoded,
                tvIdentityPublicKey = localIdentity,
                phoneIdentityPublicKey = peerIdentity,
            )
        } else {
            HandshakeTranscript(
                tvEphemeralPublicKey = remote.encoded,
                phoneEphemeralPublicKey = local.getPublic().encoded,
                tvIdentityPublicKey = peerIdentity,
                phoneIdentityPublicKey = localIdentity,
            )
        }
    }

    private fun matchesStoredPeer(key: PublicKey): Boolean =
        keyStore.getPeer(peerAlias)?.publicKey?.encoded?.contentEquals(key.encoded) == true

    private fun decodeKeys(frame: SessionFrame): Pair<PublicKey, PublicKey>? = runCatching {
        val identityBytes = frame.identityPublicKey ?: return@runCatching null
        val ephemeralBytes = frame.ephemeralPublicKey ?: return@runCatching null
        CompanionCrypto.decodePublicKey(identityBytes) to
            CompanionCrypto.decodePublicKey(ephemeralBytes)
    }.getOrNull()

    private fun rejected(reason: SessionRejectReason): SessionHandshakeResult.Rejected =
        SessionHandshakeResult.Rejected(reason)

    private enum class State {
        IDLE,
        WAITING_FOR_HELLO,
        WAITING_FOR_RESPONSE,
        WAITING_FOR_CONFIRMATION,
        WAITING_FOR_ACK,
        ESTABLISHED,
    }
}
