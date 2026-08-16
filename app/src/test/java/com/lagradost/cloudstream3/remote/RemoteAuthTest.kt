package com.lagradost.cloudstream3.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAuthTest {
    private val token = RemoteAuth.newToken()

    private fun envelope(
        version: Int = 2,
        deviceId: String = "device",
        requestId: String = "request",
        timestampMs: Long = 1_000L,
        type: RemoteMessageType = RemoteMessageType.PLAY,
        payload: kotlinx.serialization.json.JsonObject? = encodePayload(KeyPayload(7)),
    ): RemoteEnvelope {
        val unsigned = RemoteEnvelope(version, requestId, deviceId, timestampMs, null, type, payload)
        return unsigned.copy(auth = RemoteAuth.sign(token, unsigned))
    }

    private fun verify(value: RemoteEnvelope, nowMs: Long = value.timestampMs, cache: RemoteAuth.ExpiringNonceCache = RemoteAuth.newNonceCache()) =
        RemoteAuth.verify(
            token = token,
            auth = value.auth,
            version = value.version,
            deviceId = value.deviceId,
            requestId = value.requestId,
            timestampMs = value.timestampMs,
            type = value.type,
            payload = value.payload,
            nowMs = nowMs,
            seenNonces = cache,
        )

    @Test
    fun `sign and verify round trip`() {
        assertEquals(AuthResult.OK, verify(envelope()))
    }

    @Test
    fun `mutating every signed envelope field fails`() {
        val original = envelope()
        assertEquals(AuthResult.BAD_AUTH, verify(original.copy(version = 3)))
        assertEquals(AuthResult.BAD_AUTH, verify(original.copy(deviceId = "other")))
        assertEquals(AuthResult.BAD_AUTH, verify(original.copy(requestId = "other")))
        assertEquals(AuthResult.BAD_AUTH, verify(original.copy(timestampMs = 1_001L)))
        assertEquals(AuthResult.BAD_AUTH, verify(original.copy(type = RemoteMessageType.KEY)))
        assertEquals(AuthResult.BAD_AUTH, verify(original.copy(payload = encodePayload(KeyPayload(8)))))
    }

    @Test
    fun `wrong token and tampered auth fail`() {
        val value = envelope()
        assertEquals(
            AuthResult.BAD_AUTH,
            RemoteAuth.verify(
                "other", value.auth, value.version, value.deviceId, value.requestId,
                value.timestampMs, value.type, value.payload, value.timestampMs,
                RemoteAuth.newNonceCache(),
            )
        )
        assertEquals(AuthResult.BAD_AUTH, verify(value.copy(auth = "garbage")))
    }

    @Test
    fun `timestamp outside window is clock skew`() {
        val value = envelope()
        assertEquals(AuthResult.CLOCK_SKEW, verify(value, value.timestampMs + RemoteAuth.MAX_CLOCK_SKEW_MS + 1))
        assertEquals(AuthResult.CLOCK_SKEW, verify(value, value.timestampMs - RemoteAuth.MAX_CLOCK_SKEW_MS - 1))
    }

    @Test
    fun `replayed request id is rejected while it is in the clock window`() {
        val cache = RemoteAuth.newNonceCache()
        val value = envelope()
        assertEquals(AuthResult.OK, verify(value, cache = cache))
        assertEquals(AuthResult.REPLAY, verify(value, cache = cache))
    }

    @Test
    fun `expired nonce can be reused only after its clock window expires`() {
        val cache = RemoteAuth.newNonceCache()
        val first = envelope(timestampMs = 1_000L)
        assertEquals(AuthResult.OK, verify(first, cache = cache))
        val second = envelope(timestampMs = 1_000L + RemoteAuth.MAX_CLOCK_SKEW_MS + 1, requestId = first.requestId)
        assertEquals(AuthResult.OK, verify(second, second.timestampMs, cache))
    }

    @Test
    fun `invalid auth does not consume nonce`() {
        val value = envelope()
        val cache = RemoteAuth.newNonceCache()
        assertEquals(AuthResult.BAD_AUTH, verify(value.copy(auth = "bad"), cache = cache))
        assertEquals(AuthResult.OK, verify(value, cache = cache))
    }

    @Test
    fun `tokens are distinct and random`() {
        val a = RemoteAuth.newToken()
        assertNotEquals(a, RemoteAuth.newToken())
        assertEquals(44, a.length)
    }

    @Test
    fun `base64 encode decode round trip and rejects invalid input`() {
        val data = ByteArray(300) { (it * 7).toByte() }
        assertTrue(RemoteAuth.decodeBase64(RemoteAuth.encodeBase64(data))!!.contentEquals(data))
        assertNull(RemoteAuth.decodeBase64("a"))
        assertNull(RemoteAuth.decodeBase64("abc&"))
        assertEquals("aGVsbG8=", RemoteAuth.encodeBase64("hello".toByteArray()))
    }
}
