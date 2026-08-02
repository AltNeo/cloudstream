package com.lagradost.cloudstream3.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAuthTest {
    private val token = RemoteAuth.newToken()

    @Test
    fun `sign and verify round trip`() {
        val requestId = "req-1"
        val ts = System.currentTimeMillis()
        val auth = RemoteAuth.sign(token, requestId, ts)

        assertEquals(
            AuthResult.OK,
            RemoteAuth.verify(token, auth, requestId, ts, ts, mutableSetOf())
        )
    }

    @Test
    fun `wrong token fails`() {
        val requestId = "req-2"
        val ts = System.currentTimeMillis()
        val auth = RemoteAuth.sign("other-token", requestId, ts)

        assertEquals(
            AuthResult.BAD_AUTH,
            RemoteAuth.verify(token, auth, requestId, ts, ts, mutableSetOf())
        )
    }

    @Test
    fun `tampered auth fails`() {
        val requestId = "req-3"
        val ts = System.currentTimeMillis()
        val auth = RemoteAuth.sign(token, requestId, ts)
        val tampered = if (auth.endsWith("A")) auth.dropLast(1) + "B" else auth + "x"

        assertEquals(
            AuthResult.BAD_AUTH,
            RemoteAuth.verify(token, tampered, requestId, ts, ts, mutableSetOf())
        )
    }

    @Test
    fun `null or empty auth fails`() {
        val ts = System.currentTimeMillis()
        assertEquals(
            AuthResult.BAD_AUTH,
            RemoteAuth.verify(token, null, "req-4", ts, ts, mutableSetOf())
        )
        assertEquals(
            AuthResult.BAD_AUTH,
            RemoteAuth.verify(token, "", "req-5", ts, ts, mutableSetOf())
        )
    }

    @Test
    fun `timestamp outside window is clock skew`() {
        val requestId = "req-6"
        val ts = System.currentTimeMillis()
        val auth = RemoteAuth.sign(token, requestId, ts)

        assertEquals(
            AuthResult.CLOCK_SKEW,
            RemoteAuth.verify(
                token, auth, requestId, ts,
                ts + RemoteAuth.MAX_CLOCK_SKEW_MS + 1_000,
                mutableSetOf(),
            )
        )
        assertEquals(
            AuthResult.CLOCK_SKEW,
            RemoteAuth.verify(
                token, auth, requestId, ts,
                ts - RemoteAuth.MAX_CLOCK_SKEW_MS - 1_000,
                mutableSetOf(),
            )
        )
    }

    @Test
    fun `replayed request id is rejected once`() {
        val requestId = "req-7"
        val ts = System.currentTimeMillis()
        val auth = RemoteAuth.sign(token, requestId, ts)
        val seen = mutableSetOf<String>()

        assertEquals(AuthResult.OK, RemoteAuth.verify(token, auth, requestId, ts, ts, seen))
        assertEquals(AuthResult.REPLAY, RemoteAuth.verify(token, auth, requestId, ts, ts, seen))
    }

    @Test
    fun `replay lru stays bounded and only valid requests consume slots`() {
        val ts = System.currentTimeMillis()
        val seen = mutableSetOf<String>()

        // Invalid auth must NOT pollute the LRU.
        assertEquals(
            AuthResult.BAD_AUTH,
            RemoteAuth.verify(token, "garbage", "invalid-1", ts, ts, seen)
        )
        assertTrue(seen.isEmpty())

        repeat(600) { i ->
            val requestId = "valid-$i"
            val auth = RemoteAuth.sign(token, requestId, ts)
            assertEquals(AuthResult.OK, RemoteAuth.verify(token, auth, requestId, ts, ts, seen))
        }
        assertTrue("LRU should stay bounded", seen.size <= 500)

        // An old-but-valid request may be replay-accepted again after eviction, which is fine;
        // the important property is the size bound.
        assertTrue(seen.size <= 500)
    }

    @Test
    fun `tokens are distinct and random`() {
        val a = RemoteAuth.newToken()
        val b = RemoteAuth.newToken()
        assertNotEquals(a, b)
        assertEquals(44, a.length) // 32 bytes -> 44 base64 chars with padding
    }

    @Test
    fun `base64 encode decode round trip`() {
        val payloads = listOf(
            byteArrayOf(),
            byteArrayOf(0),
            "hello".toByteArray(),
            ByteArray(32) { it.toByte() },
            ByteArray(300) { (it * 7).toByte() },
        )
        payloads.forEach { data ->
            val encoded = RemoteAuth.encodeBase64(data)
            val decoded = RemoteAuth.decodeBase64(encoded)
            assertTrue("round trip for size ${data.size}", decoded.contentEquals(data))
        }
    }

    @Test
    fun `base64 rejects invalid input`() {
        assertNull(RemoteAuth.decodeBase64("a"))
        assertNull(RemoteAuth.decodeBase64("abc&"))
    }

    @Test
    fun `base64 matches standard alphabet`() {
        assertEquals("aGVsbG8=", RemoteAuth.encodeBase64("hello".toByteArray()))
        assertEquals("AQID", RemoteAuth.encodeBase64(byteArrayOf(1, 2, 3)))
    }
}
