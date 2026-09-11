package com.lagradost.cloudstream3.companion.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class HandshakeTest {
    @Test
    fun `pairing completes with mutual pin confirmation and stores peers`() {
        val fixture = PairingFixture()
        val hello = fixture.phone.start()
        val response = fixture.tv.accept(hello, SOURCE)
        fixture.phone.setPairingPin(PIN)
        val confirmation = fixture.phone.accept((response as PairingHandshakeResult.Send).frame)
        val tvEstablished = fixture.tv.accept(
            (confirmation as PairingHandshakeResult.Send).frame,
            SOURCE,
        )
        val phoneEstablished = fixture.phone.accept(
            (tvEstablished as PairingHandshakeResult.SendAndEstablished).frame,
        )

        assertTrue(fixture.tv.isEstablished())
        assertTrue(fixture.phone.isEstablished())
        assertTrue((phoneEstablished as PairingHandshakeResult.Established).peer.deviceName == "TV")
        assertEquals("Phone", tvEstablished.peer.deviceName)
        assertTrue(fixture.tvStore.getPeer(tvEstablished.peer.alias) != null)
        assertTrue(fixture.phoneStore.getPeer(phoneEstablished.peer.alias) != null)
    }

    @Test
    fun `wrong pin consumes attempts and sixth hello is locked`() {
        val now = longArrayOf(0L)
        val window = PairingWindow(clock = { now[0] })
        window.open(PIN, 0L)
        val tvStore = InMemoryKeyStoreImpl()
        val tv = PairingHandshakeCoordinator(
            role = CompanionRole.TV,
            identityAlias = "tv",
            deviceName = "TV",
            keyStore = tvStore,
            pairingWindow = window,
            clock = { now[0] },
        )

        repeat(5) { index ->
            val phone = PairingHandshakeCoordinator(
                role = CompanionRole.PHONE,
                identityAlias = "phone-$index",
                deviceName = "Phone",
                keyStore = InMemoryKeyStoreImpl(),
                clock = { now[0] },
            )
            val response = tv.accept(phone.start(), SOURCE)
            phone.setPairingPin("654321")
            val confirmation = phone.accept((response as PairingHandshakeResult.Send).frame)
            val result = tv.accept((confirmation as PairingHandshakeResult.Send).frame, SOURCE)
            assertEquals(
                PairingRejectReason.WRONG_CONFIRMATION,
                (result as PairingHandshakeResult.Rejected).reason,
            )
        }

        val sixth = PairingHandshakeCoordinator(
            role = CompanionRole.PHONE,
            identityAlias = "phone-six",
            deviceName = "Phone",
            keyStore = InMemoryKeyStoreImpl(),
        )
        val result = tv.accept(sixth.start(), SOURCE)
        assertEquals(
            PairingRejectReason.ATTEMPT_LIMIT,
            (result as PairingHandshakeResult.Rejected).reason,
        )
    }

    @Test
    fun `manual pairing stays pending until tv approval`() {
        var now = 0L
        val window = PairingWindow(clock = { now })
        window.open(PIN, now)
        val tvStore = InMemoryKeyStoreImpl()
        val phoneStore = InMemoryKeyStoreImpl()
        val tv = PairingHandshakeCoordinator(
            role = CompanionRole.TV,
            identityAlias = "tv",
            deviceName = "TV",
            keyStore = tvStore,
            pairingWindow = window,
            clock = { now },
            approvalRequired = true,
            approvalTimeoutMs = 100L,
        )
        val phone = PairingHandshakeCoordinator(
            role = CompanionRole.PHONE,
            identityAlias = "phone",
            deviceName = "Phone",
            keyStore = phoneStore,
            clock = { now },
        )

        val response = tv.accept(phone.start(), SOURCE) as PairingHandshakeResult.Send
        phone.setPairingPin(PIN)
        val confirmation = phone.accept(response.frame) as PairingHandshakeResult.Send
        val pending = tv.accept(confirmation.frame, SOURCE)
            as PairingHandshakeResult.PendingApproval

        assertEquals(PairingApprovalState.PENDING, tv.approvalState())
        assertTrue(!tv.isEstablished())
        assertTrue(tvStore.getPeer(pending.peer.alias) == null)

        val tvEstablished = tv.approvePending() as PairingHandshakeResult.SendAndEstablished
        val phoneEstablished = phone.accept(tvEstablished.frame)
        assertEquals(PairingApprovalState.APPROVED, tv.approvalState())
        assertTrue(tv.isEstablished())
        assertTrue(tvStore.getPeer(pending.peer.alias) != null)
        assertTrue(phoneEstablished is PairingHandshakeResult.Established)
    }

    @Test
    fun `manual pairing rejection and timeout do not persist peer`() {
        var now = 0L
        val window = PairingWindow(clock = { now })
        window.open(PIN, now)
        val tvStore = InMemoryKeyStoreImpl()
        val phoneStore = InMemoryKeyStoreImpl()
        val tv = PairingHandshakeCoordinator(
            role = CompanionRole.TV,
            identityAlias = "tv",
            deviceName = "TV",
            keyStore = tvStore,
            pairingWindow = window,
            clock = { now },
            approvalRequired = true,
            approvalTimeoutMs = 100L,
        )
        val phone = PairingHandshakeCoordinator(
            role = CompanionRole.PHONE,
            identityAlias = "phone",
            deviceName = "Phone",
            keyStore = phoneStore,
            clock = { now },
        )

        val response = tv.accept(phone.start(), SOURCE) as PairingHandshakeResult.Send
        phone.setPairingPin(PIN)
        val confirmation = phone.accept(response.frame) as PairingHandshakeResult.Send
        val pending = tv.accept(confirmation.frame, SOURCE)
            as PairingHandshakeResult.PendingApproval
        assertEquals(
            PairingRejectReason.APPROVAL_REJECTED,
            tv.rejectPending().reason,
        )
        assertEquals(PairingApprovalState.REJECTED, tv.approvalState())
        assertTrue(tvStore.getPeer(pending.peer.alias) == null)

        val timeoutWindow = PairingWindow(clock = { now })
        timeoutWindow.open(PIN, now)
        val timeoutTv = PairingHandshakeCoordinator(
            role = CompanionRole.TV,
            identityAlias = "tv-timeout",
            deviceName = "TV",
            keyStore = InMemoryKeyStoreImpl(),
            pairingWindow = timeoutWindow,
            clock = { now },
            approvalRequired = true,
            approvalTimeoutMs = 100L,
        )
        val timeoutPhone = PairingHandshakeCoordinator(
            role = CompanionRole.PHONE,
            identityAlias = "phone-timeout",
            deviceName = "Phone",
            keyStore = InMemoryKeyStoreImpl(),
            clock = { now },
        )
        val timeoutResponse = timeoutTv.accept(timeoutPhone.start(), SOURCE)
            as PairingHandshakeResult.Send
        timeoutPhone.setPairingPin(PIN)
        val timeoutConfirmation = timeoutPhone.accept(timeoutResponse.frame)
            as PairingHandshakeResult.Send
        timeoutTv.accept(timeoutConfirmation.frame, SOURCE)
            as PairingHandshakeResult.PendingApproval
        now = 100L
        assertEquals(PairingApprovalState.TIMED_OUT, timeoutTv.approvalState())
        assertEquals(
            PairingRejectReason.APPROVAL_TIMEOUT,
            (timeoutTv.approvePending() as PairingHandshakeResult.Rejected).reason,
        )
    }

    @Test
    fun `pairing window expires and limits attempts atomically`() {
        var now = 0L
        val window = PairingWindow(clock = { now }, durationMs = 100L, maxAttempts = 5)
        window.open(PIN, now)
        repeat(5) {
            assertEquals(PairingAttemptResult.ACCEPTED, window.registerAttempt("source"))
        }
        assertEquals(PairingAttemptResult.LOCKED, window.registerAttempt("source"))
        now = 100L
        assertEquals(PairingAttemptResult.EXPIRED, window.registerAttempt("source"))
    }

    @Test
    fun `session handshake authenticates known peer and derives usable records`() {
        val fixture = PairingFixture()
        val hello = fixture.phone.start()
        val response = fixture.tv.accept(hello, SOURCE)
        fixture.phone.setPairingPin(PIN)
        val confirmation = fixture.phone.accept((response as PairingHandshakeResult.Send).frame)
        val tvPaired = fixture.tv.accept(
            (confirmation as PairingHandshakeResult.Send).frame,
            SOURCE,
        )
            as PairingHandshakeResult.SendAndEstablished
        val phonePaired = fixture.phone.accept(tvPaired.frame) as PairingHandshakeResult.Established

        val phoneSession = SessionHandshakeCoordinator(
            role = CompanionRole.PHONE,
            initiator = true,
            identityAlias = "phone",
            peerAlias = phonePaired.peer.alias,
            keyStore = fixture.phoneStore,
        )
        val tvSession = SessionHandshakeCoordinator(
            role = CompanionRole.TV,
            initiator = false,
            identityAlias = "tv",
            peerAlias = tvPaired.peer.alias,
            keyStore = fixture.tvStore,
        )
        val sessionHello = phoneSession.start()
        val sessionResponse = tvSession.accept(sessionHello)
            as SessionHandshakeResult.Send
        val sessionConfirmation = phoneSession.accept(sessionResponse.frame)
            as SessionHandshakeResult.Send
        val tvReady = tvSession.accept(sessionConfirmation.frame)
            as SessionHandshakeResult.SendAndEstablished
        val phoneReady = phoneSession.accept(tvReady.frame)
            as SessionHandshakeResult.Established

        val phoneChannel = SecureChannelState()
        val tvChannel = SecureChannelState()
        phoneChannel.establish(phoneReady.session.recordLayer)
        tvChannel.establish(tvReady.session.recordLayer)
        val record = phoneChannel.encryptApplicationRecord("PING".encodeToByteArray())
        assertEquals("PING", tvChannel.decryptApplicationRecord(record).decodeToString())
        assertTrue(phoneSession.isEstablished())
        assertTrue(tvSession.isEstablished())
    }

    @Test
    fun `unknown long term identity is rejected before session signatures`() {
        val store = InMemoryKeyStoreImpl()
        val unknown = InMemoryKeyStoreImpl().getOrCreateIdentity("unknown")
        val responder = SessionHandshakeCoordinator(
            role = CompanionRole.TV,
            initiator = false,
            identityAlias = "tv",
            peerAlias = "missing-peer",
            keyStore = store,
        )
        val hello = SessionFrame(
            kind = SessionFrameKind.HELLO,
            senderRole = CompanionRole.PHONE,
            ephemeralPublicKey = CompanionCrypto.generateP256KeyPair().getPublic().encoded,
            identityPublicKey = unknown.getPublic().encoded,
        )

        val result = responder.accept(hello) as SessionHandshakeResult.Rejected
        assertEquals(SessionRejectReason.UNKNOWN_PEER, result.reason)
    }

    @Test
    fun `channel rejects plaintext application frames after establishment`() {
        val channel = SecureChannelState()
        assertThrows(ChannelStateException::class.java) {
            channel.rejectPlaintextApplicationFrame()
        }
        assertEquals(ChannelPhase.CLOSED, channel.phase())
        assertThrows(ChannelStateException::class.java) {
            channel.acceptHandshakeFrame()
        }
    }

    @Test
    fun `revocation removes peer and closes every registered connection`() {
        val store = InMemoryKeyStoreImpl()
        val peer = CompanionCrypto.generateP256KeyPair()
        store.savePeer("tv", peer.getPublic(), "TV", 1L)
        val registry = RevocationRegistry(store)
        var firstClosed = false
        var secondClosed = false
        val first = registry.register("tv") { firstClosed = true }
        registry.register("tv") { secondClosed = true }

        assertTrue(registry.revoke("tv"))
        assertTrue(firstClosed)
        assertTrue(secondClosed)
        assertTrue(store.getPeer("tv") == null)
        first.close()
        assertTrue(!registry.revoke("tv"))
    }

    private class PairingFixture {
        val tvStore = InMemoryKeyStoreImpl()
        val phoneStore = InMemoryKeyStoreImpl()
        private val window = PairingWindow(clock = { 0L })
        val tv = PairingHandshakeCoordinator(
            role = CompanionRole.TV,
            identityAlias = "tv",
            deviceName = "TV",
            keyStore = tvStore,
            pairingWindow = window,
            clock = { 0L },
        )
        val phone = PairingHandshakeCoordinator(
            role = CompanionRole.PHONE,
            identityAlias = "phone",
            deviceName = "Phone",
            keyStore = phoneStore,
            clock = { 0L },
        )

        init {
            window.open(PIN, 0L)
        }
    }

    companion object {
        private const val PIN = "123456"
        private const val SOURCE = "192.168.1.10"
    }
}
