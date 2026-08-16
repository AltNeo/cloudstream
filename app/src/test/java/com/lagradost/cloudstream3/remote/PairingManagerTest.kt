package com.lagradost.cloudstream3.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PairingManagerTest {
    private fun publicKey(): String = RemoteCrypto.publicKeyBase64(RemoteCrypto.newPairingKeyPair())

    @Test
    fun `new hello replaces the previous session for the same device atomically`() {
        val deviceId = "pairing-test-${UUID.randomUUID()}"
        val first = PairingManager.startPairingSession(deviceId, "phone", publicKey())
        val second = PairingManager.startPairingSession(deviceId, "phone", publicKey())

        assertNotEquals(first.sessionId, second.sessionId)
        assertNull(PairingManager.getPairingSession(first.sessionId))
        assertTrue(PairingManager.getPairingSession(second.sessionId) === second)
        PairingManager.removePairingSession(second.sessionId)
    }

    @Test
    fun `parallel pin guesses cannot exceed the attempt limit`() {
        val session = PairingManager.startPairingSession(
            "pairing-test-${UUID.randomUUID()}",
            "phone",
            publicKey(),
        )
        val executor = Executors.newFixedThreadPool(8)
        repeat(8) { executor.submit { PairingManager.verifyPinProof(session, "wrong") } }
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertNull(PairingManager.getPairingSession(session.sessionId))
    }

    @Test
    fun `pairing hello rate limit is per source`() {
        val source = "rate-test-${UUID.randomUUID()}"
        repeat(4) { assertTrue(PairingManager.allowPairingAttempt(source, verify = false)) }
        assertFalse(PairingManager.allowPairingAttempt(source, verify = false))
    }
}
