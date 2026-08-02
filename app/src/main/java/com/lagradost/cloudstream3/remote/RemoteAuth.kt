package com.lagradost.cloudstream3.remote

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

enum class AuthResult {
    OK, BAD_AUTH, CLOCK_SKEW, REPLAY,
}

/**
 * HMAC-SHA256 signing and verification for the companion protocol.
 *
 * `auth = Base64(HMAC_SHA256(token, "$requestId:$timestampMs"))`.
 *
 * The implementation is pure Kotlin/JVM (javax.crypto) so it is unit-testable
 * without Robolectric. Base64 is a small local RFC 4648 codec because
 * `android.util.Base64` is not available in plain JVM unit tests and
 * `java.util.Base64` requires API 26+ (minSdk is 23).
 *
 * Note: token storage is plain SharedPreferences (MODE_PRIVATE). Keystore /
 * EncryptedSharedPreferences is future hardening, not v1.
 */
object RemoteAuth {
    const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L
    private const val HMAC_ALGO = "HmacSHA256"
    private const val REPLAY_LRU_SIZE = 500

    fun sign(token: String, requestId: String, timestampMs: Long): String {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), HMAC_ALGO))
        val data = "$requestId:$timestampMs".toByteArray(Charsets.UTF_8)
        return encodeBase64(mac.doFinal(data))
    }

    /**
     * @param seenRequestIds per-device LRU of requestIds (size capped at [REPLAY_LRU_SIZE]).
     * Only requests that pass the HMAC check consume a slot, so unauthenticated attackers
     * cannot evict legitimate entries.
     */
    fun verify(
        token: String,
        auth: String?,
        requestId: String,
        timestampMs: Long,
        nowMs: Long,
        seenRequestIds: MutableSet<String>,
    ): AuthResult {
        if (auth.isNullOrEmpty() || requestId.isBlank()) return AuthResult.BAD_AUTH
        if (abs(nowMs - timestampMs) > MAX_CLOCK_SKEW_MS) return AuthResult.CLOCK_SKEW
        if (!constantTimeEquals(sign(token, requestId, timestampMs), auth)) {
            return AuthResult.BAD_AUTH
        }
        synchronized(seenRequestIds) {
            if (!seenRequestIds.add(requestId)) return AuthResult.REPLAY
            if (seenRequestIds.size > REPLAY_LRU_SIZE) {
                // Drop the oldest half to keep the LRU bounded.
                val iterator = seenRequestIds.iterator()
                var dropped = 0
                while (iterator.hasNext() && dropped < REPLAY_LRU_SIZE / 2) {
                    iterator.next()
                    iterator.remove()
                    dropped++
                }
            }
        }
        return AuthResult.OK
    }

    /** 32 random bytes, Base64 encoded — the shared secret handed out at pairing time. */
    fun newToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return encodeBase64(bytes)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    // ------------------------------------------------------------------
    // RFC 4648 Base64 (standard alphabet, with padding, no line wrapping)
    // ------------------------------------------------------------------

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encodeBase64(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            sb.append(ALPHABET[b0 ushr 2])
            sb.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            sb.append(if (i + 1 < data.size) ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
            sb.append(if (i + 2 < data.size) ALPHABET[b2 and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decodeBase64(text: String): ByteArray? {
        val cleaned = text.filterNot { it == '\n' || it == '\r' }
        if (cleaned.isEmpty()) return ByteArray(0)
        var padding = 0
        var end = cleaned.length
        while (end > 0 && cleaned[end - 1] == '=') {
            padding++
            end--
        }
        val chars = cleaned.substring(0, end)
        if (chars.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream((chars.length / 4) * 3)
        var i = 0
        while (i < chars.length) {
            val c0 = decodeChar(chars[i]) ?: return null
            val c1 = decodeChar(chars.getOrNull(i + 1)) ?: return null
            val isFullGroup = i + 3 < chars.length
            val c2 = decodeChar(chars.getOrNull(i + 2))
            val c3 = decodeChar(chars.getOrNull(i + 3))
            // A full 4-char group must decode completely; only the final group may be short
            // (2 or 3 data chars, padding stripped above).
            if (isFullGroup && (c2 == null || c3 == null)) return null
            if (c2 == null && c3 != null) return null
            val triple = (c0 shl 18) or (c1 shl 12) or ((c2 ?: 0) shl 6) or (c3 ?: 0)
            out.write((triple ushr 16) and 0xFF)
            if (c2 != null) out.write((triple ushr 8) and 0xFF)
            if (c3 != null) out.write(triple and 0xFF)
            i += 4
        }
        return out.toByteArray()
    }

    private fun decodeChar(c: Char?): Int? {
        if (c == null) return null
        return when (c) {
            in 'A'..'Z' -> c - 'A'
            in 'a'..'z' -> c - 'a' + 26
            in '0'..'9' -> c - '0' + 52
            '+' -> 62
            '/' -> 63
            else -> null
        }
    }
}
