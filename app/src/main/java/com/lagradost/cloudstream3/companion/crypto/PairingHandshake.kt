package com.lagradost.cloudstream3.companion.crypto

import java.security.KeyPair
import java.security.PublicKey

enum class PairingFrameKind {
    HELLO,
    RESPONSE,
    CONFIRMATION,
}

data class PairingFrame(
    val kind: PairingFrameKind,
    val senderRole: CompanionRole,
    val ephemeralPublicKey: ByteArray? = null,
    val identityPublicKey: ByteArray? = null,
    val deviceName: String? = null,
    val confirmation: ByteArray? = null,
)

enum class PairingAttemptResult {
    ACCEPTED,
    EXPIRED,
    LOCKED,
    SOURCE_RATE_LIMITED,
}

/**
 * Atomic pairing-window state. The PIN is only exposed to the local TV UI and is
 * never included in a [PairingFrame].
 */
class PairingWindow(
    private val clock: () -> Long = System::currentTimeMillis,
    private val durationMs: Long = DEFAULT_DURATION_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val maxAttemptsPerSource: Int = maxAttempts,
) {
    private val lock = Any()
    private var pin: String? = null
    private var expiresAtMs = 0L
    private var attempts = 0
    private val attemptsBySource = mutableMapOf<String, Int>()

    fun open(pin: String = generatePin(), nowMs: Long = clock()): String = synchronized(lock) {
        require(pin.length == 6 && pin.all { it in '0'..'9' }) { "PIN must be six digits" }
        require(durationMs > 0L) { "pairing duration must be positive" }
        require(maxAttempts > 0 && maxAttemptsPerSource > 0) { "attempt limits must be positive" }
        this.pin = pin
        expiresAtMs = nowMs + durationMs
        attempts = 0
        attemptsBySource.clear()
        pin
    }

    fun currentPin(nowMs: Long = clock()): String? = synchronized(lock) {
        if (pin == null || nowMs >= expiresAtMs) {
            pin = null
            return@synchronized null
        }
        pin
    }

    fun registerAttempt(source: String, nowMs: Long = clock()): PairingAttemptResult =
        synchronized(lock) {
            if (currentPinLocked(nowMs) == null) return@synchronized PairingAttemptResult.EXPIRED
            if (attempts >= maxAttempts) return@synchronized PairingAttemptResult.LOCKED
            val sourceAttempts = attemptsBySource[source] ?: 0
            if (sourceAttempts >= maxAttemptsPerSource) {
                return@synchronized PairingAttemptResult.SOURCE_RATE_LIMITED
            }
            attempts++
            attemptsBySource[source] = sourceAttempts + 1
            PairingAttemptResult.ACCEPTED
        }

    fun close() = synchronized(lock) {
        pin = null
        expiresAtMs = 0L
        attempts = 0
        attemptsBySource.clear()
    }

    private fun currentPinLocked(nowMs: Long): String? {
        if (pin == null || nowMs >= expiresAtMs) {
            pin = null
            return null
        }
        return pin
    }

    companion object {
        const val DEFAULT_DURATION_MS = 2 * 60 * 1000L
        const val DEFAULT_MAX_ATTEMPTS = 5

        private fun generatePin(): String =
            (100000 + java.security.SecureRandom().nextInt(900000)).toString()
    }
}

sealed class PairingHandshakeResult {
    data class Send(val frame: PairingFrame) : PairingHandshakeResult()

    data class PendingApproval(
        val peer: PairedPeer,
        val expiresAtMs: Long,
    ) : PairingHandshakeResult()

    data class SendAndEstablished(
        val frame: PairingFrame,
        val peer: PairedPeer,
    ) : PairingHandshakeResult()

    data class Established(val peer: PairedPeer) : PairingHandshakeResult()

    data class Rejected(val reason: PairingRejectReason) : PairingHandshakeResult()
}

enum class PairingRejectReason {
    INVALID_STATE,
    INVALID_FRAME,
    EXPIRED,
    ATTEMPT_LIMIT,
    WRONG_CONFIRMATION,
    APPROVAL_REJECTED,
    APPROVAL_TIMEOUT,
}

enum class PairingApprovalState {
    NONE,
    PENDING,
    APPROVED,
    REJECTED,
    TIMED_OUT,
}

/**
 * Pure pairing state machine. The caller is responsible for encoding frames and
 * closing the socket on [PairingHandshakeResult.Rejected].
 */
class PairingHandshakeCoordinator(
    private val role: CompanionRole,
    identityAlias: String,
    private val deviceName: String,
    private val keyStore: CompanionKeyStore,
    private val pairingWindow: PairingWindow? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val approvalRequired: Boolean = false,
    private val approvalTimeoutMs: Long = DEFAULT_APPROVAL_TIMEOUT_MS,
) {
    init {
        require(approvalTimeoutMs > 0L) { "approval timeout must be positive" }
    }

    private val identity = keyStore.getOrCreateIdentity(identityAlias)
    private var state = State.IDLE
    private var localEphemeral: KeyPair? = null
    private var remoteIdentity: PublicKey? = null
    private var remoteEphemeral: PublicKey? = null
    private var remoteDeviceName = ""
    private var transcript: HandshakeTranscript? = null
    private var pairingKey: ByteArray? = null
    private var source = ""
    private var pendingPeer: PairedPeer? = null
    private var approvalExpiresAtMs = 0L
    private var approvalOutcome = PairingApprovalState.NONE

    fun start(): PairingFrame {
        check(role == CompanionRole.PHONE) { "only the phone initiates pairing" }
        check(state == State.IDLE || state == State.FAILED) { "pairing is already in progress" }
        approvalOutcome = PairingApprovalState.NONE
        pendingPeer = null
        approvalExpiresAtMs = 0L
        localEphemeral = CompanionCrypto.generateP256KeyPair()
        state = State.WAITING_FOR_RESPONSE
        return PairingFrame(
            kind = PairingFrameKind.HELLO,
            senderRole = role,
            ephemeralPublicKey = localEphemeral!!.getPublic().encoded,
            identityPublicKey = identity.getPublic().encoded,
            deviceName = deviceName,
        )
    }

    fun accept(
        frame: PairingFrame,
        source: String = this.source,
    ): PairingHandshakeResult = when (role) {
        CompanionRole.TV -> acceptAsTv(frame, source)
        CompanionRole.PHONE -> acceptAsPhone(frame)
    }

    fun isEstablished(): Boolean = state == State.ESTABLISHED

    fun approvalState(nowMs: Long = clock()): PairingApprovalState {
        if (approvalOutcome == PairingApprovalState.PENDING && nowMs >= approvalExpiresAtMs) {
            expireApproval()
        }
        return approvalOutcome
    }

    fun approvePending(nowMs: Long = clock()): PairingHandshakeResult {
        check(role == CompanionRole.TV) { "only the TV approves pairing" }
        if (approvalState(nowMs) != PairingApprovalState.PENDING) {
            return rejected(
                if (approvalOutcome == PairingApprovalState.TIMED_OUT) {
                    PairingRejectReason.APPROVAL_TIMEOUT
                } else {
                    PairingRejectReason.INVALID_STATE
                },
            )
        }
        val peer = pendingPeer ?: return rejected(PairingRejectReason.INVALID_STATE)
        val frame = confirmationFrame()
        keyStore.savePeer(peer.alias, peer.publicKey, peer.deviceName, peer.pairedAtMs)
        state = State.ESTABLISHED
        approvalOutcome = PairingApprovalState.APPROVED
        pairingWindow?.close()
        clearTransient()
        return PairingHandshakeResult.SendAndEstablished(frame, peer)
    }

    fun rejectPending(nowMs: Long = clock()): PairingHandshakeResult.Rejected {
        if (approvalState(nowMs) == PairingApprovalState.TIMED_OUT) {
            return rejected(PairingRejectReason.APPROVAL_TIMEOUT)
        }
        if (approvalOutcome != PairingApprovalState.PENDING) {
            return rejected(PairingRejectReason.INVALID_STATE)
        }
        approvalOutcome = PairingApprovalState.REJECTED
        state = State.FAILED
        pendingPeer = null
        clearTransient()
        return PairingHandshakeResult.Rejected(PairingRejectReason.APPROVAL_REJECTED)
    }

    /** Supplies the PIN entered locally on the phone; it is never serialized. */
    fun setPairingPin(pin: String) {
        require(pin.length == 6 && pin.all { it in '0'..'9' }) { "PIN must be six digits" }
        enteredPin = pin
    }

    private fun acceptAsTv(frame: PairingFrame, source: String): PairingHandshakeResult {
        if (state == State.PENDING_APPROVAL) {
            return rejected(PairingRejectReason.INVALID_STATE)
        }
        if (frame.kind == PairingFrameKind.HELLO) {
            val window = pairingWindow ?: return rejected(PairingRejectReason.EXPIRED)
            return when (window.registerAttempt(source)) {
                PairingAttemptResult.ACCEPTED -> acceptTvHello(frame, source)
                PairingAttemptResult.EXPIRED -> rejected(PairingRejectReason.EXPIRED)
                PairingAttemptResult.LOCKED,
                PairingAttemptResult.SOURCE_RATE_LIMITED,
                -> rejected(PairingRejectReason.ATTEMPT_LIMIT)
            }
        }
        if (state != State.WAITING_FOR_CONFIRMATION) {
            return rejected(PairingRejectReason.INVALID_STATE)
        }
        if (frame.kind != PairingFrameKind.CONFIRMATION ||
            frame.senderRole != CompanionRole.PHONE ||
            frame.confirmation == null
        ) {
            return rejected(PairingRejectReason.INVALID_FRAME)
        }

        val key = pairingKey ?: return rejected(PairingRejectReason.INVALID_STATE)
        val hash = transcript?.hash() ?: return rejected(PairingRejectReason.INVALID_STATE)
        if (!CompanionCrypto.verifyPairingConfirmation(
                key,
                hash,
                CompanionRole.PHONE,
                frame.confirmation,
            )
        ) {
            state = State.FAILED
            return rejected(PairingRejectReason.WRONG_CONFIRMATION)
        }

        val peer = buildRemotePeer()
        if (approvalRequired) {
            pendingPeer = peer
            approvalExpiresAtMs = clock() + approvalTimeoutMs
            approvalOutcome = PairingApprovalState.PENDING
            state = State.PENDING_APPROVAL
            return PairingHandshakeResult.PendingApproval(peer, approvalExpiresAtMs)
        }
        return establishTvPeer(peer, key, hash)
    }

    private fun acceptTvHello(frame: PairingFrame, source: String): PairingHandshakeResult {
        val remote = decodeKeys(frame) ?: return rejected(PairingRejectReason.INVALID_FRAME)
        val pin = pairingWindow?.currentPin()
            ?: return rejected(PairingRejectReason.EXPIRED)
        localEphemeral = CompanionCrypto.generateP256KeyPair()
        remoteIdentity = remote.first
        remoteEphemeral = remote.second
        remoteDeviceName = frame.deviceName.orEmpty()
        this.source = source
        transcript = HandshakeTranscript(
            tvEphemeralPublicKey = localEphemeral!!.getPublic().encoded,
            phoneEphemeralPublicKey = remoteEphemeral!!.encoded,
            tvIdentityPublicKey = identity.getPublic().encoded,
            phoneIdentityPublicKey = remoteIdentity!!.encoded,
        )
        pairingKey = CompanionCrypto.derivePairingKey(
            sessionSecret = CompanionCrypto.performEcdh(
                localEphemeral!!.getPrivate(),
                remoteEphemeral!!,
            ),
            transcriptHash = transcript!!.hash(),
            pin = pin,
        )
        state = State.WAITING_FOR_CONFIRMATION
        return PairingHandshakeResult.Send(
            PairingFrame(
                kind = PairingFrameKind.RESPONSE,
                senderRole = CompanionRole.TV,
                ephemeralPublicKey = localEphemeral!!.getPublic().encoded,
                identityPublicKey = identity.getPublic().encoded,
                deviceName = deviceName,
            ),
        )
    }

    private fun acceptAsPhone(frame: PairingFrame): PairingHandshakeResult {
        if (state == State.WAITING_FOR_CONFIRMATION &&
            frame.kind == PairingFrameKind.CONFIRMATION &&
            frame.senderRole == CompanionRole.TV &&
            frame.confirmation != null
        ) {
            val key = pairingKey ?: return rejected(PairingRejectReason.INVALID_STATE)
            val hash = transcript?.hash() ?: return rejected(PairingRejectReason.INVALID_STATE)
            if (!CompanionCrypto.verifyPairingConfirmation(
                    key,
                    hash,
                    CompanionRole.TV,
                    frame.confirmation,
                )
            ) {
                state = State.FAILED
                return rejected(PairingRejectReason.WRONG_CONFIRMATION)
            }
            state = State.ESTABLISHED
            enteredPin = null
            val peer = saveRemotePeer()
            clearTransient()
            return PairingHandshakeResult.Established(peer)
        }
        if (state != State.WAITING_FOR_RESPONSE ||
            frame.kind != PairingFrameKind.RESPONSE ||
            frame.senderRole != CompanionRole.TV
        ) {
            return rejected(PairingRejectReason.INVALID_STATE)
        }
        val remote = decodeKeys(frame) ?: return rejected(PairingRejectReason.INVALID_FRAME)
        val pin = currentPairingPin()
            ?: return rejected(PairingRejectReason.EXPIRED)
        remoteIdentity = remote.first
        remoteEphemeral = remote.second
        remoteDeviceName = frame.deviceName.orEmpty()
        val local = localEphemeral ?: return rejected(PairingRejectReason.INVALID_STATE)
        transcript = HandshakeTranscript(
            tvEphemeralPublicKey = remoteEphemeral!!.encoded,
            phoneEphemeralPublicKey = local.getPublic().encoded,
            tvIdentityPublicKey = remoteIdentity!!.encoded,
            phoneIdentityPublicKey = identity.getPublic().encoded,
        )
        pairingKey = CompanionCrypto.derivePairingKey(
            sessionSecret = CompanionCrypto.performEcdh(local.getPrivate(), remoteEphemeral!!),
            transcriptHash = transcript!!.hash(),
            pin = pin,
        )
        state = State.WAITING_FOR_CONFIRMATION
        return PairingHandshakeResult.Send(
            PairingFrame(
                kind = PairingFrameKind.CONFIRMATION,
                senderRole = CompanionRole.PHONE,
                confirmation = CompanionCrypto.pairingConfirmation(
                    pairingKey!!,
                    transcript!!.hash(),
                    CompanionRole.PHONE,
                ),
            ),
        )
    }

    private fun establishTvPeer(
        peer: PairedPeer,
        key: ByteArray,
        hash: ByteArray,
    ): PairingHandshakeResult.SendAndEstablished {
        val finalConfirmation = PairingFrame(
            kind = PairingFrameKind.CONFIRMATION,
            senderRole = CompanionRole.TV,
            confirmation = CompanionCrypto.pairingConfirmation(key, hash, CompanionRole.TV),
        )
        keyStore.savePeer(peer.alias, peer.publicKey, peer.deviceName, peer.pairedAtMs)
        state = State.ESTABLISHED
        pairingWindow?.close()
        clearTransient()
        return PairingHandshakeResult.SendAndEstablished(finalConfirmation, peer)
    }

    private fun confirmationFrame(): PairingFrame {
        val key = pairingKey ?: error("missing pairing key")
        val hash = transcript?.hash() ?: error("missing pairing transcript")
        return PairingFrame(
            kind = PairingFrameKind.CONFIRMATION,
            senderRole = CompanionRole.TV,
            confirmation = CompanionCrypto.pairingConfirmation(key, hash, CompanionRole.TV),
        )
    }

    private fun buildRemotePeer(): PairedPeer {
        val key = remoteIdentity ?: error("missing peer identity")
        return PairedPeer(
            alias = fingerprint(key),
            publicKey = key,
            deviceName = remoteDeviceName,
            pairedAtMs = clock(),
        )
    }

    private fun saveRemotePeer(): PairedPeer {
        val peer = buildRemotePeer()
        keyStore.savePeer(peer.alias, peer.publicKey, peer.deviceName, peer.pairedAtMs)
        return peer
    }

    private fun expireApproval() {
        approvalOutcome = PairingApprovalState.TIMED_OUT
        state = State.FAILED
        pendingPeer = null
        pairingWindow?.close()
        clearTransient()
    }

    private fun clearTransient() {
        pairingKey?.fill(0)
        pairingKey = null
        enteredPin = null
        localEphemeral = null
        remoteIdentity = null
        remoteEphemeral = null
        remoteDeviceName = ""
        transcript = null
        source = ""
        pendingPeer = null
        approvalExpiresAtMs = 0L
    }

    private fun decodeKeys(frame: PairingFrame): Pair<PublicKey, PublicKey>? = runCatching {
        val identityBytes = frame.identityPublicKey ?: return@runCatching null
        val ephemeralBytes = frame.ephemeralPublicKey ?: return@runCatching null
        CompanionCrypto.decodePublicKey(identityBytes) to
            CompanionCrypto.decodePublicKey(ephemeralBytes)
    }.getOrNull()

    private fun rejected(reason: PairingRejectReason): PairingHandshakeResult.Rejected {
        if (state != State.ESTABLISHED) {
            if (approvalOutcome == PairingApprovalState.PENDING) {
                approvalOutcome = PairingApprovalState.REJECTED
            }
            state = State.FAILED
            clearTransient()
        }
        return PairingHandshakeResult.Rejected(reason)
    }

    private var enteredPin: String? = null

    private fun currentPairingPin(): String? = pairingWindow?.currentPin() ?: enteredPin

    private enum class State {
        IDLE,
        WAITING_FOR_RESPONSE,
        WAITING_FOR_CONFIRMATION,
        PENDING_APPROVAL,
        ESTABLISHED,
        FAILED,
    }

    companion object {
        const val DEFAULT_APPROVAL_TIMEOUT_MS = 30_000L

        private fun fingerprint(key: PublicKey): String =
            CompanionCrypto.sha256(key.encoded).joinToString("") { "%02x".format(it) }
    }
}
