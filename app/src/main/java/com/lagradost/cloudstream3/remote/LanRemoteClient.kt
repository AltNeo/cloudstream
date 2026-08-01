package com.lagradost.cloudstream3.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class LanRemoteEndpoint(
    val name: String,
    val host: String,
    val port: Int = LanRemoteProtocol.PORT,
)

object LanRemoteClient {
    private const val PREFS_NAME = "lan_remote"
    private const val HOST_KEY = "selected_host"
    private const val PORT_KEY = "selected_port"
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val READ_TIMEOUT_MS = 5_000

    fun selectedEndpoint(context: Context?): LanRemoteEndpoint? {
        context ?: return null
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = preferences.getString(HOST_KEY, null)?.takeIf(String::isNotBlank)
            ?: return null
        val port = preferences.getInt(PORT_KEY, LanRemoteProtocol.PORT)
        return LanRemoteEndpoint(host, host, port)
    }

    fun selectEndpoint(context: Context, endpoint: LanRemoteEndpoint) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(HOST_KEY, endpoint.host)
            .putInt(PORT_KEY, endpoint.port)
            .apply()
    }

    suspend fun ping(endpoint: LanRemoteEndpoint): LanRemoteResponse {
        return send(endpoint, LanRemoteRequest(command = LanRemoteCommand.PING))
    }

    suspend fun send(
        context: Context,
        request: LanRemoteRequest,
    ): LanRemoteResponse {
        val endpoint = selectedEndpoint(context)
            ?: throw IllegalStateException("No CloudStream TV is connected")
        return send(endpoint, request)
    }

    suspend fun send(
        endpoint: LanRemoteEndpoint,
        request: LanRemoteRequest,
    ): LanRemoteResponse = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
            LanRemoteProtocol.write(
                java.io.DataOutputStream(socket.getOutputStream()),
                request,
            )
            LanRemoteProtocol.read(java.io.DataInputStream(socket.getInputStream()))
        }
    }
}
