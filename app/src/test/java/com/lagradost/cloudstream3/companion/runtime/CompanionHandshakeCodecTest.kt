package com.lagradost.cloudstream3.companion.runtime

import com.lagradost.cloudstream3.companion.crypto.CompanionRole
import com.lagradost.cloudstream3.companion.crypto.PairingFrame
import com.lagradost.cloudstream3.companion.crypto.PairingFrameKind
import com.lagradost.cloudstream3.companion.crypto.SessionFrame
import com.lagradost.cloudstream3.companion.crypto.SessionFrameKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionHandshakeCodecTest {
    @Test
    fun `pairing handshake frame round trips without exposing binary corruption`() {
        val frame = PairingFrame(
            kind = PairingFrameKind.CONFIRMATION,
            senderRole = CompanionRole.PHONE,
            ephemeralPublicKey = byteArrayOf(0, 1, 2, -1),
            identityPublicKey = byteArrayOf(9, 8, 7),
            deviceName = "Phone",
            confirmation = byteArrayOf(4, 5, 6),
        )

        val decoded = CompanionHandshakeCodec.decode(CompanionHandshakeCodec.encode(frame))
        require(decoded is CompanionHandshakeCodec.Decoded.Pairing)
        assertEquals(frame.kind, decoded.frame.kind)
        assertEquals(frame.senderRole, decoded.frame.senderRole)
        assertEquals(frame.deviceName, decoded.frame.deviceName)
        assertArrayEquals(frame.ephemeralPublicKey, decoded.frame.ephemeralPublicKey)
        assertArrayEquals(frame.identityPublicKey, decoded.frame.identityPublicKey)
        assertArrayEquals(frame.confirmation, decoded.frame.confirmation)
    }

    @Test
    fun `session handshake frame round trips`() {
        val frame = SessionFrame(
            kind = SessionFrameKind.RESPONSE,
            senderRole = CompanionRole.TV,
            ephemeralPublicKey = byteArrayOf(3, 4, 5),
            identityPublicKey = byteArrayOf(6, 7, 8),
            signature = byteArrayOf(10, 11, 12),
        )

        val decoded = CompanionHandshakeCodec.decode(CompanionHandshakeCodec.encode(frame))
        require(decoded is CompanionHandshakeCodec.Decoded.Session)
        assertEquals(frame.kind, decoded.frame.kind)
        assertEquals(frame.senderRole, decoded.frame.senderRole)
        assertArrayEquals(frame.ephemeralPublicKey, decoded.frame.ephemeralPublicKey)
        assertArrayEquals(frame.identityPublicKey, decoded.frame.identityPublicKey)
        assertArrayEquals(frame.signature, decoded.frame.signature)
    }
}
