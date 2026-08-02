package com.lagradost.cloudstream3.remote

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.remote.server.CommandHandlers
import com.lagradost.cloudstream3.remote.server.NowPlayingHub
import com.lagradost.cloudstream3.remote.sync.LibrarySyncManager
import com.lagradost.cloudstream3.ui.PairingOverlayDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject

/**
 * TV-side server (plan §5.1). Public API stays `start/stop`; internals are rewritten:
 * - one coroutine per accepted socket (no more sequential accept loop),
 * - v1/v2 frame detection: v1 PING answered for old phones, everything else "upgrade-required",
 * - auth gate: PING / PAIR_* unauthenticated, all other types require a valid HMAC,
 * - SUBSCRIBE sockets stay open and stream RemoteEvents until disconnect,
 * - cold-start PLAY / OPEN_PAGE via PendingCommandQueue.
 */
object LanRemoteServer {
    private const val TAG = "LanRemoteServer"

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val libraryListenerAttached = AtomicBoolean(false)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext

        // TV-side progress report-back: capture DataStoreHelper writes and push LIBRARY_DELTA (plan §6.4/§9.3).
        if (libraryListenerAttached.compareAndSet(false, true)) {
            MainActivity.libraryChangedEvent += { key ->
                LibrarySyncManager.onLibraryChanged(appContext, key)
            }
        }

        scope.launch {
            try {
                val socket = ServerSocket(LanRemoteProtocol.PORT).apply {
                    reuseAddress = true
                }
                serverSocket = socket
                mainHandler.post { registerService(appContext) }
                while (isActive) {
                    val client = socket.accept()
                    scope.launch {
                        runCatching { handleClient(appContext, client) }
                    }
                }
            } catch (_: SocketException) {
                started.set(false)
            } catch (_: Throwable) {
                started.set(false)
            }
        }
    }

    fun stop() {
        if (!started.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        val listener = registrationListener
        if (listener != null) {
            runCatching { nsdManager?.unregisterService(listener) }
        }
        registrationListener = null
        nsdManager = null
    }

    // ------------------------------------------------------------------

    private suspend fun handleClient(context: Context, client: Socket) {
        client.use { socket ->
            socket.soTimeout = 5_000
            val element = runCatching {
                LanRemoteProtocol.readFrame(DataInputStream(socket.getInputStream()))
            }.getOrElse { error ->
                Log.w(TAG, "Invalid frame from ${socket.inetAddress}: ${error.message}")
                writeErrorReply(socket, "unknown", "invalid-request")
                return
            }

            if (LanRemoteProtocol.isV2Frame(element)) {
                val envelope = runCatching {
                    LanRemoteProtocol.json.decodeFromString<RemoteEnvelope>(element.toString())
                }.getOrElse {
                    writeErrorReply(socket, "unknown", "invalid-envelope")
                    return
                }
                handleV2(context, socket, envelope)
            } else {
                handleV1(context, socket, element)
            }
        }
    }

    private suspend fun handleV1(context: Context, socket: Socket, element: kotlinx.serialization.json.JsonElement) {
        val request = runCatching {
            LanRemoteProtocol.json.decodeFromString<LanRemoteRequest>(element.toString())
        }.getOrElse {
            writeErrorReplyV1(socket, "unknown", "invalid-request")
            return
        }
        val response = when (request.command) {
            // Old phones still get a meaningful PING answer; everything else must upgrade.
            LanRemoteCommand.PING -> LanRemoteResponse(
                requestId = request.requestId,
                accepted = true,
                message = deviceName(),
            )

            else -> LanRemoteResponse(
                requestId = request.requestId,
                accepted = false,
                message = "upgrade-required",
            )
        }
        runCatching {
            LanRemoteProtocol.write(DataOutputStream(socket.getOutputStream()), response)
        }
    }

    private suspend fun handleV2(context: Context, socket: Socket, envelope: RemoteEnvelope) {
        val output = DataOutputStream(socket.getOutputStream())
        when (envelope.type) {
            RemoteMessageType.PING -> {
                val paired = PairingManager.getPhoneToken(envelope.deviceId) != null
                val reply = RemoteReply(
                    requestId = envelope.requestId,
                    accepted = true,
                    payload = encodePayload(CommandHandlers.buildDeviceInfo(context, paired)),
                )
                runCatching { LanRemoteProtocol.write(output, reply) }
            }

            RemoteMessageType.PAIR_HELLO -> handlePairHello(context, output, envelope)
            RemoteMessageType.PAIR_VERIFY -> handlePairVerify(context, output, envelope)

            RemoteMessageType.SUBSCRIBE -> handleSubscribe(context, socket, output, envelope)

            else -> {
                val auth = authenticate(envelope)
                if (auth != AuthResult.OK) {
                    val error = when (auth) {
                        AuthResult.CLOCK_SKEW -> "clock-skew"
                        else -> "unauthenticated"
                    }
                    runCatching {
                        LanRemoteProtocol.write(
                            output,
                            RemoteReply(requestId = envelope.requestId, accepted = false, error = error),
                        )
                    }
                    return
                }
                val reply = CommandHandlers.handle(context, envelope)
                runCatching { LanRemoteProtocol.write(output, reply) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Pairing
    // ------------------------------------------------------------------

    private fun handlePairHello(
        context: Context,
        output: DataOutputStream,
        envelope: RemoteEnvelope,
    ) {
        val hello = envelope.payloadAs<PairHelloRequest>()
        if (hello == null || hello.deviceId.isBlank()) {
            runCatching {
                LanRemoteProtocol.write(output, RemoteReply(requestId = envelope.requestId, accepted = false, error = "invalid-pairing"))
            }
            return
        }
        if (!PairingManager.isPairingAllowed(context)) {
            runCatching {
                LanRemoteProtocol.write(output, RemoteReply(requestId = envelope.requestId, accepted = false, error = "pairing-disabled"))
            }
            return
        }
        // A device that is already paired must unpair first: this prevents a LAN attacker
        // from re-issuing a PAIR_HELLO with a victim's deviceId to overwrite their token
        // (review S1). Re-pairing after the TV-side unpair works normally.
        if (PairingManager.getPairedPhones().containsKey(hello.deviceId)) {
            runCatching {
                LanRemoteProtocol.write(output, RemoteReply(requestId = envelope.requestId, accepted = false, error = "already-paired"))
            }
            return
        }
        val session = PairingManager.startPairingSession(hello.deviceId, hello.deviceName)
        mainHandler.post {
            if (CommonActivity.activity == null) {
                launchApp(context)
            }
            PairingOverlayDialog.show(session)
        }
        // Debug/e2e hook (adb logcat only in debug builds): lets an adb-driven test read the PIN.
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "CompanionPairing PIN for ${hello.deviceName}: ${session.pin}")
        }
        runCatching {
            LanRemoteProtocol.write(
                output,
                RemoteReply(
                    requestId = envelope.requestId,
                    accepted = true,
                    payload = encodePayload(PairHelloReply(session.sessionId, 120_000)),
                ),
            )
        }
    }

    private fun handlePairVerify(
        context: Context,
        output: DataOutputStream,
        envelope: RemoteEnvelope,
    ) {
        val verify = envelope.payloadAs<PairVerifyRequest>()
        if (verify == null) {
            runCatching {
                LanRemoteProtocol.write(output, RemoteReply(requestId = envelope.requestId, accepted = false, error = "invalid-pairing"))
            }
            return
        }
        val session = PairingManager.getPairingSession(verify.pairingSessionId)
        if (session == null) {
            runCatching {
                LanRemoteProtocol.write(output, RemoteReply(requestId = envelope.requestId, accepted = false, error = "pairing-expired"))
            }
            return
        }
        // The verifier must be the same device that started the session: the PIN is only
        // proof of physical presence, not of identity (review S1).
        if (envelope.deviceId != session.phoneDeviceId) {
            runCatching {
                LanRemoteProtocol.write(output, RemoteReply(requestId = envelope.requestId, accepted = false, error = "device-mismatch"))
            }
            return
        }
        when (PairingManager.verifyPin(session, verify.pin)) {
            PairingManager.PinResult.OK -> {
                val token = RemoteAuth.newToken()
                PairingManager.registerPhone(
                    PairedPhone(session.phoneDeviceId, session.phoneName, token)
                )
                mainHandler.post { PairingOverlayDialog.dismiss() }
                runCatching {
                    LanRemoteProtocol.write(
                        output,
                        RemoteReply(
                            requestId = envelope.requestId,
                            accepted = true,
                            payload = encodePayload(
                                PairVerifyReply(token, CommandHandlers.buildDeviceInfo(context, paired = true))
                            ),
                        ),
                    )
                }
            }

            PairingManager.PinResult.WRONG -> runCatching {
                LanRemoteProtocol.write(output, RemoteReply(requestId = envelope.requestId, accepted = false, error = "invalid-pin"))
            }
            PairingManager.PinResult.EXPIRED -> runCatching {
                LanRemoteProtocol.write(output, RemoteReply(requestId = envelope.requestId, accepted = false, error = "pairing-expired"))
            }
            PairingManager.PinResult.BLOCKED -> runCatching {
                LanRemoteProtocol.write(output, RemoteReply(requestId = envelope.requestId, accepted = false, error = "pairing-blocked"))
            }
        }
    }

    // ------------------------------------------------------------------
    // SUBSCRIBE event channel
    // ------------------------------------------------------------------

    private suspend fun handleSubscribe(
        context: Context,
        socket: Socket,
        output: DataOutputStream,
        envelope: RemoteEnvelope,
    ) {
        val auth = authenticate(envelope)
        if (auth != AuthResult.OK) {
            runCatching {
                LanRemoteProtocol.write(
                    output,
                    RemoteReply(requestId = envelope.requestId, accepted = false, error = "unauthenticated"),
                )
            }
            return
        }
        runCatching {
            // Frame-atomicity: broadcast writes use the same socket-level lock (NowPlayingHub.writeEvent).
            synchronized(socket) {
                LanRemoteProtocol.write(output, RemoteReply(requestId = envelope.requestId, accepted = true))
            }
        }
        NowPlayingHub.registerSubscriber(envelope.deviceId, socket)

        // Hold the socket open; NowPlayingHub streams events. Detect disconnect via EOF.
        try {
            socket.soTimeout = 30_000
            val input = DataInputStream(socket.getInputStream())
            while (true) {
                val b = input.read()
                if (b == -1) break
            }
        } catch (_: Exception) {
            // disconnected / timed out
        }
        NowPlayingHub.unregisterSubscriber(envelope.deviceId, socket)
    }

    // ------------------------------------------------------------------

    private fun authenticate(envelope: RemoteEnvelope): AuthResult {
        val token = PairingManager.getPhoneToken(envelope.deviceId) ?: return AuthResult.BAD_AUTH
        return RemoteAuth.verify(
            token = token,
            auth = envelope.auth,
            requestId = envelope.requestId,
            timestampMs = envelope.timestampMs,
            nowMs = System.currentTimeMillis(),
            seenRequestIds = PairingManager.replaySetFor(envelope.deviceId),
        )
    }

    private fun writeErrorReply(socket: Socket, requestId: String, error: String) {
        runCatching {
            LanRemoteProtocol.write(
                DataOutputStream(socket.getOutputStream()),
                RemoteReply(requestId = requestId, accepted = false, error = error),
            )
        }
    }

    private fun writeErrorReplyV1(socket: Socket, requestId: String, message: String) {
        runCatching {
            LanRemoteProtocol.write(
                DataOutputStream(socket.getOutputStream()),
                LanRemoteResponse(requestId = requestId, accepted = false, message = message),
            )
        }
    }

    private fun launchApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
    }

    private fun registerService(context: Context) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = manager
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        manager.registerService(
            NsdServiceInfo().apply {
                serviceName = "CloudStream-${deviceName()}"
                serviceType = LanRemoteProtocol.SERVICE_TYPE
                port = LanRemoteProtocol.PORT
            },
            NsdManager.PROTOCOL_DNS_SD,
            listener,
        )
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
}
