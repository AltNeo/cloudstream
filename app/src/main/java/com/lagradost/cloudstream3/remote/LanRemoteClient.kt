package com.lagradost.cloudstream3.remote

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context as appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** Kept for the discovery flow; a TV the phone knows about on the LAN. */
data class LanRemoteEndpoint(
    val name: String,
    val host: String,
    val port: Int = LanRemoteProtocol.PORT,
)

object LanRemoteClient {
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val READ_TIMEOUT_MS = 5_000

    /** Signed one-shot v2 envelope to a specific paired TV. */
    suspend fun send(
        tv: PairedTv,
        type: RemoteMessageType,
        payload: Any? = null,
    ): RemoteReply = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val envelope = RemoteEnvelope(
            requestId = requestId,
            deviceId = PairingManager.myDeviceId(appContext ?: throw IllegalStateException("No app context")),
            timestampMs = now,
            auth = RemoteAuth.sign(tv.token, requestId, now),
            type = type,
            payload = payload?.let { encodePayloadForMessage(type, it) },
        )
        sendRaw(tv.host, tv.port, envelope)
    }

    /** Unauthenticated send (PING / PAIR_*). */
    suspend fun sendUnauthenticated(
        host: String,
        port: Int,
        type: RemoteMessageType,
        payload: Any? = null,
    ): RemoteReply = withContext(Dispatchers.IO) {
        val envelope = RemoteEnvelope(
            requestId = UUID.randomUUID().toString(),
            deviceId = runCatching {
                appContext?.let { PairingManager.myDeviceId(it) } ?: ""
            }.getOrDefault(""),
            type = type,
            payload = payload?.let { encodePayloadForMessage(type, it) },
        )
        sendRaw(host, port, envelope)
    }

    /** v2 PING to a specific TV. */
    suspend fun ping(tv: PairedTv): RemoteReply = send(tv, RemoteMessageType.PING)

    /** v2 PING to an arbitrary host (manual entry / discovery). */
    suspend fun ping(host: String, port: Int): RemoteReply =
        sendUnauthenticated(host, port, RemoteMessageType.PING)

    /** Uses the active TV; throws IllegalStateException when none is paired. */
    suspend fun sendActive(type: RemoteMessageType, payload: Any? = null): RemoteReply {
        val tv = PairingManager.getActiveTv()
            ?: throw IllegalStateException("No CloudStream TV is paired")
        return send(tv, type, payload)
    }

    /**
     * Legacy `lan_remote` prefs from the v1 cut (plan §5.2): there is no token in there,
     * so it can only seed the manual host field; the user must pair again.
     */
    fun legacyEndpoint(context: Context): Pair<String, Int>? {
        val prefs = context.getSharedPreferences("lan_remote", Context.MODE_PRIVATE)
        val host = prefs.getString("selected_host", null)?.takeIf(String::isNotBlank) ?: return null
        val port = prefs.getInt("selected_port", LanRemoteProtocol.PORT)
        return host to port
    }

    private suspend fun sendRaw(host: String, port: Int, envelope: RemoteEnvelope): RemoteReply =
        withContext(Dispatchers.IO) {
            Socket().use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                LanRemoteProtocol.write(DataOutputStream(socket.getOutputStream()), envelope)
                LanRemoteProtocol.read<RemoteReply>(DataInputStream(socket.getInputStream()))
            }
        }

    private fun encodePayloadForMessage(type: RemoteMessageType, payload: Any) = when (type) {
        RemoteMessageType.PAIR_HELLO -> encodePayload(payload as PairHelloRequest)
        RemoteMessageType.PAIR_VERIFY -> encodePayload(payload as PairVerifyRequest)
        RemoteMessageType.KEY -> encodePayload(payload as KeyPayload)
        RemoteMessageType.TEXT -> encodePayload(payload as TextPayload)
        RemoteMessageType.PLAY -> encodePayload(payload as PlayPayload)
        RemoteMessageType.PLAYER_CMD -> encodePayload(payload as PlayerCmdPayload)
        RemoteMessageType.OPEN_PAGE -> encodePayload(payload as OpenPagePayload)
        RemoteMessageType.SYNC_EXTENSIONS -> encodePayload(payload as ExtensionSyncPayload)
        RemoteMessageType.EXT_FILE_START -> encodePayload(payload as ExtFileStartPayload)
        RemoteMessageType.EXT_FILE_CHUNK -> encodePayload(payload as ExtFileChunkPayload)
        RemoteMessageType.EXT_FILE_END -> encodePayload(payload as ExtFileEndPayload)
        RemoteMessageType.SYNC_LIBRARY -> encodePayload(payload as LibrarySyncPayload)
        else -> error("Unsupported payload for message: $type")
    }
}
