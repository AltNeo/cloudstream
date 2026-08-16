package com.lagradost.cloudstream3.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

enum class AuthResult {
    OK, BAD_AUTH, CLOCK_SKEW, REPLAY, NONCE_CACHE_FULL,
}

/**
 * HMAC-SHA256 signing and verification for the companion protocol.
 *
 * The MAC covers a length-delimited canonical serialization of every envelope field that can
 * affect command meaning. Payload object keys are sorted recursively, making equivalent JSON
 * representations sign identically while preventing concatenation ambiguities.
 */
object RemoteAuth {
    const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L
    private const val HMAC_ALGO = "HmacSHA256"

    fun sign(token: String, envelope: RemoteEnvelope): String = sign(
        token = token,
        version = envelope.version,
        deviceId = envelope.deviceId,
        requestId = envelope.requestId,
        timestampMs = envelope.timestampMs,
        type = envelope.type,
        payload = envelope.payload,
    )

    fun sign(
        token: String,
        version: Int,
        deviceId: String,
        requestId: String,
        timestampMs: Long,
        type: RemoteMessageType,
        payload: JsonObject?,
    ): String {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), HMAC_ALGO))
        return encodeBase64(mac.doFinal(canonicalEnvelope(version, deviceId, requestId, timestampMs, type, payload)))
    }

    /** A bounded nonce cache that retains every nonce while its timestamp is in the clock window. */
    class ExpiringNonceCache(private val maxEntries: Int = 4096) {
        private val entries = LinkedHashMap<String, Long>()

        @Synchronized
        fun addIfNew(requestId: String, timestampMs: Long, nowMs: Long): Boolean {
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (abs(nowMs - entry.value) > MAX_CLOCK_SKEW_MS) iterator.remove()
            }
            if (entries.containsKey(requestId)) return false
            if (entries.size >= maxEntries) return false
            entries[requestId] = timestampMs
            return true
        }

        @Synchronized
        fun size(): Int = entries.size

        @Synchronized
        fun contains(requestId: String): Boolean = entries.containsKey(requestId)

        @Synchronized
        fun clear() = entries.clear()
    }

    fun newNonceCache(maxEntries: Int = 4096): ExpiringNonceCache = ExpiringNonceCache(maxEntries)

    fun verify(
        token: String,
        auth: String?,
        version: Int,
        deviceId: String,
        requestId: String,
        timestampMs: Long,
        type: RemoteMessageType,
        payload: JsonObject?,
        nowMs: Long,
        seenNonces: ExpiringNonceCache,
    ): AuthResult {
        if (auth.isNullOrEmpty() || requestId.isBlank()) return AuthResult.BAD_AUTH
        if (abs(nowMs - timestampMs) > MAX_CLOCK_SKEW_MS) return AuthResult.CLOCK_SKEW
        if (!constantTimeProofEquals(
                sign(token, version, deviceId, requestId, timestampMs, type, payload),
                auth,
            )
        ) return AuthResult.BAD_AUTH
        if (!seenNonces.addIfNew(requestId, timestampMs, nowMs)) {
            return if (seenNonces.contains(requestId)) AuthResult.REPLAY else AuthResult.NONCE_CACHE_FULL
        }
        return AuthResult.OK
    }

    /** 32 random bytes, Base64 encoded — the bearer token handed out at pairing time. */
    fun newToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return encodeBase64(bytes)
    }

    private fun canonicalEnvelope(
        version: Int,
        deviceId: String,
        requestId: String,
        timestampMs: Long,
        type: RemoteMessageType,
        payload: JsonObject?,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(version)
            writeField(data, deviceId)
            writeField(data, requestId)
            data.writeLong(timestampMs)
            writeField(data, type.name)
            writeField(data, payload?.let(::canonicalJson) ?: "<null>")
        }
        return output.toByteArray()
    }

    private fun writeField(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                "${quoteJson(key)}:${canonicalJson(value)}"
            }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]", transform = ::canonicalJson)
        is JsonPrimitive -> element.toString()
    }

    private fun quoteJson(value: String): String =
        RemoteAuthJson.encodeToString(JsonPrimitive(value))

    private val RemoteAuthJson = kotlinx.serialization.json.Json { encodeDefaults = true }

    internal fun constantTimeProofEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

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
        var end = cleaned.length
        while (end > 0 && cleaned[end - 1] == '=') end--
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

    private fun decodeChar(c: Char?): Int? = when (c) {
        null -> null
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> null
    }
}
