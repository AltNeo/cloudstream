package com.lagradost.cloudstream3.remote

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    @SerialName("lastSeen") val lastSeen: Long = System.currentTimeMillis(),
)

/** A phone allowed to control this TV (TV side storage). */
@Serializable
data class PairedPhone(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("name") val name: String,
    @SerialName("token") val token: String,
    @SerialName("pairedAt") val pairedAt: Long = System.currentTimeMillis(),
)

/** In-memory PIN session, TV side. */
data class PairingSession(
    val sessionId: String,
    val pin: String,
    val phoneDeviceId: String,
    val phoneName: String,
    val expiresAtMs: Long,
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

    private val sessions = ConcurrentHashMap<String, PairingSession>()
    private val deviceReplay = ConcurrentHashMap<String, MutableSet<String>>()

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
        if (deviceId == null) removeKey(ACTIVE_TV_KEY) else setKey(ACTIVE_TV_KEY, deviceId)
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
            registerTv(tv.copy(host = host, port = port, lastSeen = System.currentTimeMillis()))
        } else {
            registerTv(tv.copy(lastSeen = System.currentTimeMillis()))
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

    fun registerPhone(phone: PairedPhone) {
        setKey(PHONES_KEY, getPairedPhones() + (phone.deviceId to phone))
    }

    fun forgetPhone(deviceId: String) {
        setKey(PHONES_KEY, getPairedPhones() - deviceId)
    }

    // ------------------------------------------------------------------
    // Pairing sessions (TV side, in-memory)
    // ------------------------------------------------------------------

    fun startPairingSession(phoneDeviceId: String, phoneName: String): PairingSession {
        // Evict expired sessions and superseded sessions for the same requester, then
        // cap the total (a LAN peer can otherwise grow the map unboundedly, review S2).
        sessions.keys.toList().forEach { id ->
            val existing = sessions[id] ?: return@forEach
            if (existing.isExpired || id == phoneDeviceId) sessions.remove(id)
        }
        while (sessions.size >= MAX_PAIRING_SESSIONS) {
            val oldest = sessions.entries.minByOrNull { it.value.expiresAtMs } ?: break
            sessions.remove(oldest.key)
        }
        val session = PairingSession(
            sessionId = UUID.randomUUID().toString(),
            pin = generatePin(),
            phoneDeviceId = phoneDeviceId,
            phoneName = phoneName,
            expiresAtMs = System.currentTimeMillis() + PIN_TTL_MS,
        )
        sessions[session.sessionId] = session
        return session
    }

    fun getPairingSession(sessionId: String): PairingSession? = sessions[sessionId]

    fun removePairingSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    enum class PinResult { OK, WRONG, EXPIRED, BLOCKED }

    fun verifyPin(session: PairingSession, pin: String): PinResult {
        if (session.isExpired) {
            sessions.remove(session.sessionId)
            return PinResult.EXPIRED
        }
        if (session.attempts >= MAX_PIN_ATTEMPTS) {
            sessions.remove(session.sessionId)
            return PinResult.BLOCKED
        }
        if (session.pin == pin.trim()) {
            sessions.remove(session.sessionId)
            return PinResult.OK
        }
        session.attempts++
        if (session.attempts >= MAX_PIN_ATTEMPTS) {
            sessions.remove(session.sessionId)
            return PinResult.BLOCKED
        }
        return PinResult.WRONG
    }

    // ------------------------------------------------------------------
    // Replay protection (server side, per device)
    // ------------------------------------------------------------------

    fun replaySetFor(deviceId: String): MutableSet<String> =
        deviceReplay.getOrPut(deviceId) { LinkedHashSet() }

    private fun generatePin(): String {
        val random = SecureRandom()
        return (1..6).joinToString("") { random.nextInt(10).toString() }
    }
}
