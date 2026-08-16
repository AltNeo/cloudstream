package com.lagradost.cloudstream3.remote

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.KeyPair
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A TV the phone is paired with (phone side storage). */
@Serializable
data class PairedTv(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("name") val name: String,
    @SerialName("host") val host: String,
    @SerialName("port") val port: Int = LanRemoteProtocol.PORT,
    @SerialName("token") val token: String,
    /** Base64 AES-GCM session key; empty means this stored pairing must be renewed. */
    @SerialName("sessionKey") val sessionKey: String = "",
    @SerialName("lastSeen") val lastSeen: Long = System.currentTimeMillis(),
)

/** A phone allowed to control this TV (TV side storage). */
@Serializable
data class PairedPhone(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("name") val name: String,
    @SerialName("token") val token: String,
    @SerialName("sessionKey") val sessionKey: String = "",
    @SerialName("pairedAt") val pairedAt: Long = System.currentTimeMillis(),
)

/** In-memory PIN session, TV side. */
data class PairingSession(
    val sessionId: String,
    val pin: String,
    val phoneDeviceId: String,
    val phoneName: String,
    val expiresAtMs: Long,
    val phonePublicKey: java.security.PublicKey,
    val tvKeyPair: KeyPair,
    var attempts: Int = 0,
) {
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAtMs
}

/**
 * Pairing + token storage for both roles (plan.md §4).
 *
 * - Phone role: stores `companion/paired_tvs` (Map<deviceId, PairedTv>) + the active TV id.
 * - TV role: stores `companion/paired_phones` (Map<deviceId, PairedPhone>) with per-device
 *   HMAC tokens, and in-memory PIN sessions.
 *
 * Storage is plain MODE_PRIVATE SharedPreferences (via CloudStreamApp.setKey/getKey).
 * Keystore / EncryptedSharedPreferences is future hardening, not v1 (owner decision, plan §15.5).
 */
object PairingManager {
    const val TVS_KEY = "companion/paired_tvs"
    const val PHONES_KEY = "companion/paired_phones"
    const val ACTIVE_TV_KEY = "companion/active_tv"
    const val ALLOW_PAIRING_KEY = "companion/allow_pairing"
    const val ALLOW_CONTROL_KEY = "companion/allow_control"
    const val DEVICE_ID_KEY = "companion/device_id"

    private const val PIN_TTL_MS = 120_000L
    private const val MAX_PIN_ATTEMPTS = 5
    /** Hard bound on concurrent in-memory pairing sessions (LAN-wide DoS guard). */
    private const val MAX_PAIRING_SESSIONS = 8

    private const val PAIRING_RATE_WINDOW_MS = 60_000L
    private const val MAX_HELLOS_PER_SOURCE = 4
    private const val MAX_VERIFIES_PER_SOURCE = 10

    private val sessionLock = Any()
    private val sessions = mutableMapOf<String, PairingSession>()
    private val pairingRateLock = Any()
    private val pairingRates = mutableMapOf<String, PairingRate>()
    private val deviceReplay = ConcurrentHashMap<String, RemoteAuth.ExpiringNonceCache>()

    private data class PairingRate(var windowStartedAtMs: Long, var hellos: Int = 0, var verifies: Int = 0)

    // ------------------------------------------------------------------
    // Role / receiver setting
    // ------------------------------------------------------------------

    fun isTelevision(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** "Allow this device to be controlled": default ON for TVs, OFF for phones. */
    fun isControlAllowed(context: Context): Boolean =
        getKey<Boolean>(ALLOW_CONTROL_KEY) ?: isTelevision(context)

    fun setControlAllowed(context: Context, allowed: Boolean) {
        setKey(ALLOW_CONTROL_KEY, allowed)
        if (allowed) {
            LanRemoteService.start(context)
        } else {
            // Changing this setting is an immediate revocation, not just a preference
            // update. The request gate remains in place for the small stop race window.
            LanRemoteServer.stop()
            context.stopService(Intent(context, LanRemoteService::class.java))
        }
    }

    /** "Allow pairing requests": default ON for TVs, OFF for phones. */
    fun isPairingAllowed(context: Context): Boolean =
        getKey<Boolean>(ALLOW_PAIRING_KEY) ?: isTelevision(context)

    fun setPairingAllowed(context: Context, allowed: Boolean) {
        setKey(ALLOW_PAIRING_KEY, allowed)
    }

    // ------------------------------------------------------------------
    // Device identity
    // ------------------------------------------------------------------

    fun myDeviceId(context: Context): String {
        getKey<String>(DEVICE_ID_KEY)?.let { return it }
        val id = UUID.randomUUID().toString()
        setKey(DEVICE_ID_KEY, id)
        return id
    }

    fun myDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    // ------------------------------------------------------------------
    // Paired TVs (phone side)
    // ------------------------------------------------------------------

    fun getPairedTvs(): Map<String, PairedTv> =
        getKey<Map<String, PairedTv>>(TVS_KEY) ?: emptyMap()

    fun getActiveTv(): PairedTv? {
        val activeId = getKey<String>(ACTIVE_TV_KEY) ?: return null
        return getPairedTvs()[activeId]
    }

    fun setActiveTv(deviceId: String?) {
        val previousId = getKey<String>(ACTIVE_TV_KEY)
        if (deviceId == null) removeKey(ACTIVE_TV_KEY) else setKey(ACTIVE_TV_KEY, deviceId)
        if (previousId != deviceId) CompanionSessionManager.onActiveTvChanged()
    }

    fun registerTv(tv: PairedTv, active: Boolean = true) {
        val updated = getPairedTvs() + (tv.deviceId to tv)
        setKey(TVS_KEY, updated)
        if (active) setActiveTv(tv.deviceId)
    }

    /** IP/host changed: deviceId is the stable identity, the endpoint is not (plan §5.4). */
    fun updateTvEndpoint(deviceId: String, host: String, port: Int) {
        val tv = getPairedTvs()[deviceId] ?: return
        if (tv.host != host || tv.port != port) {
            registerTv(
                tv.copy(host = host, port = port, lastSeen = System.currentTimeMillis()),
                active = false,
            )
        } else {
            registerTv(tv.copy(lastSeen = System.currentTimeMillis()), active = false)
        }
    }

    fun forgetTv(deviceId: String) {
        val updated = getPairedTvs() - deviceId
        setKey(TVS_KEY, updated)
        if (getKey<String>(ACTIVE_TV_KEY) == deviceId) {
            setActiveTv(updated.keys.firstOrNull())
        }
    }

    // ------------------------------------------------------------------
    // Paired phones (TV side)
    // ------------------------------------------------------------------

    fun getPairedPhones(): Map<String, PairedPhone> =
        getKey<Map<String, PairedPhone>>(PHONES_KEY) ?: emptyMap()

    fun getPhoneToken(deviceId: String): String? = getPairedPhones()[deviceId]?.token

    fun getPhoneSessionKey(deviceId: String): ByteArray? =
        getPairedPhones()[deviceId]?.sessionKey?.takeIf(String::isNotEmpty)?.let(RemoteAuth::decodeBase64)

    fun registerPhone(phone: PairedPhone) {
        setKey(PHONES_KEY, getPairedPhones() + (phone.deviceId to phone))
    }

    fun forgetPhone(deviceId: String, revokeSockets: Boolean = true) {
        setKey(PHONES_KEY, getPairedPhones() - deviceId)
        // Token deletion alone does not revoke an already authenticated SUBSCRIBE socket.
        com.lagradost.cloudstream3.remote.server.NowPlayingHub.revokeDevice(deviceId)
        if (revokeSockets) LanRemoteServer.revokeDeviceSockets(deviceId)
    }

    // ------------------------------------------------------------------
    // Pairing sessions (TV side, in-memory)
    // ------------------------------------------------------------------

    fun startPairingSession(
        phoneDeviceId: String,
        phoneName: String,
        phonePublicKey: String,
    ): PairingSession = synchronized(sessionLock) {
        // Cleanup and deduplication happen in the same critical section as insertion. In
        // particular, do not evict a valid session just because an unauthenticated HELLO
        // filled the table.
        sessions.values.removeAll { it.isExpired || it.phoneDeviceId == phoneDeviceId }
        if (sessions.size >= MAX_PAIRING_SESSIONS) {
            throw IllegalStateException("Pairing capacity reached")
        }
        val session = PairingSession(
            sessionId = UUID.randomUUID().toString(),
            pin = generatePin(),
            phoneDeviceId = phoneDeviceId,
            phoneName = phoneName,
            expiresAtMs = System.currentTimeMillis() + PIN_TTL_MS,
            phonePublicKey = RemoteCrypto.decodePublicKey(phonePublicKey)
                ?: throw IllegalArgumentException("Invalid pairing public key"),
            tvKeyPair = RemoteCrypto.newPairingKeyPair(),
        )
        sessions[session.sessionId] = session
        session
    }

    fun getPairingSession(sessionId: String): PairingSession? = synchronized(sessionLock) {
        sessions[sessionId]
    }

    fun removePairingSession(sessionId: String) = synchronized(sessionLock) {
        sessions.remove(sessionId)
    }

    /** Source-level throttling for unauthenticated pairing traffic. */
    fun allowPairingAttempt(source: String, verify: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean =
        synchronized(pairingRateLock) {
            val rate = pairingRates.getOrPut(source) { PairingRate(nowMs) }
            if (nowMs - rate.windowStartedAtMs >= PAIRING_RATE_WINDOW_MS ||
                nowMs < rate.windowStartedAtMs
            ) {
                rate.windowStartedAtMs = nowMs
                rate.hellos = 0
                rate.verifies = 0
            }
            if (verify) {
                if (rate.verifies >= MAX_VERIFIES_PER_SOURCE) return@synchronized false
                rate.verifies++
            } else {
                if (rate.hellos >= MAX_HELLOS_PER_SOURCE) return@synchronized false
                rate.hellos++
            }
            pairingRates.entries.removeIf { nowMs - it.value.windowStartedAtMs > PAIRING_RATE_WINDOW_MS }
            true
        }

    enum class PinResult { OK, WRONG, EXPIRED, BLOCKED }

    fun sessionKey(session: PairingSession): ByteArray = RemoteCrypto.derivePairingKey(
        privateKey = session.tvKeyPair.private,
        publicKey = session.phonePublicKey,
        pin = session.pin,
        sessionId = session.sessionId,
        phoneDeviceId = session.phoneDeviceId,
    )

    fun verifyPinProof(session: PairingSession, proof: String): PinResult = synchronized(sessionLock) {
        // The lookup, attempt count, and removal are one transaction. A stale session object
        // can never verify after another request has consumed or replaced it.
        if (sessions[session.sessionId] !== session) return@synchronized PinResult.EXPIRED
        if (session.isExpired) {
            sessions.remove(session.sessionId)
            return@synchronized PinResult.EXPIRED
        }
        if (session.attempts >= MAX_PIN_ATTEMPTS) {
            sessions.remove(session.sessionId)
            return@synchronized PinResult.BLOCKED
        }
        val key = sessionKey(session)
        val expected = RemoteCrypto.proof(key, session.sessionId, session.phoneDeviceId)
        if (RemoteAuth.constantTimeProofEquals(expected, proof)) {
            sessions.remove(session.sessionId)
            return@synchronized PinResult.OK
        }
        session.attempts++
        if (session.attempts >= MAX_PIN_ATTEMPTS) {
            sessions.remove(session.sessionId)
            return@synchronized PinResult.BLOCKED
        }
        PinResult.WRONG
    }

    // ------------------------------------------------------------------
    // Replay protection (server side, per device)
    // ------------------------------------------------------------------

    fun replaySetFor(deviceId: String): RemoteAuth.ExpiringNonceCache =
        deviceReplay.getOrPut(deviceId) { RemoteAuth.newNonceCache() }

    private fun generatePin(): String {
        val random = SecureRandom()
        return (1..6).joinToString("") { random.nextInt(10).toString() }
    }
}
