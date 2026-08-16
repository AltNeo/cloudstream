package com.lagradost.cloudstream3.remote.ui

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.remote.LanRemoteClient
import com.lagradost.cloudstream3.remote.PairHelloReply
import com.lagradost.cloudstream3.remote.PairHelloRequest
import com.lagradost.cloudstream3.remote.PairVerifyReply
import com.lagradost.cloudstream3.remote.PairVerifyRequest
import com.lagradost.cloudstream3.remote.PairingManager
import com.lagradost.cloudstream3.remote.PairedTv
import com.lagradost.cloudstream3.remote.RemoteAuth
import com.lagradost.cloudstream3.remote.RemoteCrypto
import com.lagradost.cloudstream3.remote.RemoteMessageType
import com.lagradost.cloudstream3.remote.payloadAs
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Phone-side pairing flow (plan §4): PAIR_HELLO -> user types the PIN shown on the TV ->
 * PAIR_VERIFY -> token stored + TV marked active.
 */
object PairingFlow {
    suspend fun pair(
        activity: Activity,
        host: String,
        port: Int,
        onStatus: (String) -> Unit,
    ): PairedTv? {
        val phoneKeyPair = RemoteCrypto.newPairingKeyPair()
        val deviceId = PairingManager.myDeviceId(activity)
        val hello = PairHelloRequest(
            deviceId = deviceId,
            deviceName = PairingManager.myDeviceName(),
            publicKey = RemoteCrypto.publicKeyBase64(phoneKeyPair),
        )
        val helloReply = runCatching {
            LanRemoteClient.sendUnauthenticated(host, port, RemoteMessageType.PAIR_HELLO, hello)
        }.getOrNull()
            ?: run {
                onStatus(activity.getString(com.lagradost.cloudstream3.R.string.remote_connection_failed))
                return null
            }
        if (!helloReply.accepted) {
            onStatus(helloReply.error ?: "Pairing rejected")
            return null
        }
        val helloPayload = helloReply.payloadAs<PairHelloReply>()
            ?: run { onStatus("Unexpected reply"); return null }

        val pin = askPin(activity) ?: run {
            onStatus(activity.getString(com.lagradost.cloudstream3.R.string.companion_pairing_cancelled))
            return null
        }

        val tvPublicKey = RemoteCrypto.decodePublicKey(helloPayload.publicKey)
            ?: run { onStatus("Unexpected pairing key"); return null }
        val sessionKey = RemoteCrypto.derivePairingKey(
            privateKey = phoneKeyPair.private,
            publicKey = tvPublicKey,
            pin = pin,
            sessionId = helloPayload.pairingSessionId,
            phoneDeviceId = deviceId,
        )
        val verifyReply = runCatching {
            LanRemoteClient.sendUnauthenticated(
                host, port, RemoteMessageType.PAIR_VERIFY,
                PairVerifyRequest(
                    pairingSessionId = helloPayload.pairingSessionId,
                    proof = RemoteCrypto.proof(sessionKey, helloPayload.pairingSessionId, deviceId),
                ),
                responseKey = sessionKey,
            )
        }.getOrNull()
            ?: run {
                onStatus(activity.getString(com.lagradost.cloudstream3.R.string.remote_connection_failed))
                return null
            }
        if (!verifyReply.accepted) {
            onStatus(verifyReply.error ?: "Pairing failed")
            return null
        }
        val verifyPayload = verifyReply.payloadAs<PairVerifyReply>()
            ?: run { onStatus("Unexpected reply"); return null }

        val tv = PairedTv(
            deviceId = verifyPayload.tv.deviceId,
            name = verifyPayload.tv.name,
            host = host,
            port = port,
            token = verifyPayload.token,
            sessionKey = RemoteAuth.encodeBase64(sessionKey),
        )
        PairingManager.registerTv(tv, active = true)
        return tv
    }

    private suspend fun askPin(activity: Activity): String? = suspendCancellableCoroutine { cont ->
        val editText = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = activity.getString(com.lagradost.cloudstream3.R.string.companion_pairing_pin_hint)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(com.lagradost.cloudstream3.R.string.companion_pairing_title))
            .setMessage(activity.getString(com.lagradost.cloudstream3.R.string.companion_pairing_instructions))
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                cont.resume(editText.text?.toString()?.trim())
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> cont.resume(null) }
            .setOnCancelListener { cont.resume(null) }
            .show()
    }
}
