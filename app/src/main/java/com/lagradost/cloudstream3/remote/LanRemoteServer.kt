package com.lagradost.cloudstream3.remote

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.remote.server.CommandHandlers
import com.lagradost.cloudstream3.remote.server.NowPlayingHub
import com.lagradost.cloudstream3.remote.sync.LibrarySyncManager
import com.lagradost.cloudstream3.ui.PairingOverlayDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.decodeFromJsonElement

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

    private const val MAX_CONCURRENT_CONNECTIONS = 32
    private const val MAX_CONNECTIONS_PER_PEER = 4
    private const val MAX_SUBSCRIBE_READ_BYTES = 64 * 1024

    private val started = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectionSlots = Semaphore(MAX_CONCURRENT_CONNECTIONS, true)
    private val peerLock = Any()
    private val peerConnections = mutableMapOf<String, Int>()
    private val socketLock = Any()
    private val activeSockets = mutableSetOf<Socket>()
    private val socketsByDevice = mutableMapOf<String, MutableSet<Socket>>()
    private var serverSocket: ServerSocket? = null
    private var serverJob: kotlinx.coroutines.Job? = null
    private var generation = 0L
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val libraryListenerAttached = AtomicBoolean(false)

    fun start(context: Context) {
        if (!PairingManager.isControlAllowed(context)) return
        val appContext = context.applicationContext
        val jobGeneration = synchronized(lifecycleLock) {
            if (!started.compareAndSet(false, true)) return
            generation++
            generation
        }

        // TV-side progress report-back: capture DataStoreHelper writes and push LIBRARY_DELTA (plan §6.4/§9.3).
        if (libraryListenerAttached.compareAndSet(false, true)) {
            MainActivity.libraryChangedEvent += { key ->
                LibrarySyncManager.onLibraryChanged(appContext, key)
            }
        }

        val job = scope.launch {
            var socket: ServerSocket? = null
            try {
                socket = ServerSocket(LanRemoteProtocol.PORT).apply { reuseAddress = true }
                val active = synchronized(lifecycleLock) {
                    if (!started.get() || generation != jobGeneration) false else {
                        LanRemoteServer.serverSocket = socket
                        true
                    }
                }
                if (!active) {
                    socket.close()
                    return@launch
                }
                mainHandler.post { registerService(appContext, jobGeneration) }
                coroutineScope {
                    while (isActive && started.get() && generation == jobGeneration) {
                        val client = socket.accept()
                        val source = client.inetAddress?.hostAddress ?: "unknown"
                        if (!tryAcquireConnection(source)) {
                            runCatching { client.close() }
                            continue
                        }
                        trackSocket(client)
                        launch {
                            try {
                                runCatching { handleClient(appContext, client, source) }
                            } finally {
                                untrackSocket(client)
                                releaseConnection(source)
                            }
                        }
                    }
                }
            } catch (_: SocketException) {
                // Closing the listener is the normal stop path.
            } catch (error: Throwable) {
                Log.w(TAG, "LAN server stopped unexpectedly", error)
            } finally {
                runCatching { socket?.close() }
                synchronized(lifecycleLock) {
                    if (generation == jobGeneration) {
                        serverSocket = null
                        serverJob = null
                        started.set(false)
                    }
                }
            }
        }
        synchronized(lifecycleLock) { serverJob = job }
    }

    fun stop() {
        val job = synchronized(lifecycleLock) {
            if (started.getAndSet(false)) generation++
            runCatching { serverSocket?.close() }
            serverSocket = null
            val current = serverJob
            serverJob = null
            current
        }
        unregisterService()
        NowPlayingHub.clearSubscribers()
        closeActiveSockets()
        job?.cancel()
        // Do not leave the old accept/handler tree alive for a rapid restart.
        if (job != null) runBlocking { job.join() }
    }

    // ------------------------------------------------------------------

    private fun tryAcquireConnection(source: String): Boolean {
        if (!connectionSlots.tryAcquire()) return false
        synchronized(peerLock) {
            val count = peerConnections[source] ?: 0
            if (count >= MAX_CONNECTIONS_PER_PEER) {
                connectionSlots.release()
                return false
            }
            peerConnections[source] = count + 1
        }
        return true
    }

    private fun releaseConnection(source: String) {
        synchronized(peerLock) {
            val count = (peerConnections[source] ?: 1) - 1
            if (count <= 0) peerConnections.remove(source) else peerConnections[source] = count
        }
        connectionSlots.release()
    }

    private fun trackSocket(socket: Socket) = synchronized(socketLock) {
        activeSockets.add(socket)
    }

    private fun bindSocketToDevice(socket: Socket, deviceId: String) = synchronized(socketLock) {
        socketsByDevice.getOrPut(deviceId) { mutableSetOf() }.add(socket)
    }

    private fun untrackSocket(socket: Socket) = synchronized(socketLock) {
        activeSockets.remove(socket)
        socketsByDevice.values.forEach { it.remove(socket) }
        socketsByDevice.entries.removeIf { it.value.isEmpty() }
    }

    private fun closeActiveSockets() {
        val sockets = synchronized(socketLock) {
            activeSockets.toList().also {
                activeSockets.clear()
                socketsByDevice.clear()
            }
        }
        sockets.forEach { runCatching { it.close() } }
    }

    /** Closes request and event sockets for a revoked phone. */
    fun revokeDeviceSockets(deviceId: String) {
        val sockets = synchronized(socketLock) {
            socketsByDevice.remove(deviceId)?.toList().orEmpty()
        }
        sockets.forEach { runCatching { it.close() } }
    }

    private suspend fun handleClient(context: Context, client: Socket, source: String) {
        client.use { socket ->
            socket.soTimeout = 5_000
            val rawElement = runCatching {
                LanRemoteProtocol.readFrame(DataInputStream(socket.getInputStream()))
            }.getOrElse { error ->
                Log.w(TAG, "Invalid frame from ${socket.inetAddress}: ${error.message}")
                writeErrorReply(socket, "unknown", "invalid-request")
                return
            }

            var transportKey: ByteArray? = null
            val element = if (LanRemoteProtocol.isEncryptedFrame(rawElement)) {
                val frame = runCatching {
                    LanRemoteProtocol.json.decodeFromJsonElement<EncryptedRemoteFrame>(rawElement)
                }.getOrElse {
                    writeErrorReply(socket, "unknown", "invalid-encrypted-frame")
                    return
                }
                val key = PairingManager.getPhoneSessionKey(frame.deviceId) ?: run {
                    writeErrorReply(socket, "unknown", "unauthenticated")
                    return
                }
                transportKey = key
                bindSocketToDevice(socket, frame.deviceId)
                runCatching { LanRemoteProtocol.decryptFrame(rawElement, key).second }.getOrElse {
                    writeErrorReply(socket, "unknown", "invalid-encrypted-frame")
                    return
                }
            } else {
                rawElement
            }

            if (LanRemoteProtocol.isV2Frame(element)) {
                val envelope = runCatching {
                    LanRemoteProtocol.json.decodeFromString<RemoteEnvelope>(element.toString())
                }.getOrElse {
                    writeErrorReply(socket, "unknown", "invalid-envelope")
                    return
                }
                bindSocketToDevice(socket, envelope.deviceId)
                if (transportKey != null) {
                    val frameDeviceId = LanRemoteProtocol.json
                        .decodeFromJsonElement<EncryptedRemoteFrame>(rawElement).deviceId
                    if (envelope.deviceId != frameDeviceId) {
                        writeErrorReply(socket, "unknown", "device-mismatch")
                        return
                    }
                }
                handleV2(context, socket, envelope, transportKey, source)
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
        val response = when {
            !PairingManager.isControlAllowed(context) -> LanRemoteResponse(
                requestId = request.requestId,
                accepted = false,
                message = "control-disabled",
            )
            // Old phones still get a meaningful PING answer; everything else must upgrade.
            request.command == LanRemoteCommand.PING -> LanRemoteResponse(
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

    private suspend fun handleV2(
        context: Context,
        socket: Socket,
        envelope: RemoteEnvelope,
        transportKey: ByteArray?,
        source: String,
    ) {
        val output = DataOutputStream(socket.getOutputStream())
        if (!PairingManager.isControlAllowed(context)) {
            writeReply(
                output,
                RemoteReply(envelope.requestId, false, error = "control-disabled"),
                transportKey,
                envelope.deviceId,
            )
            return
        }
        fun writeReply(value: RemoteReply) = runCatching {
            writeReply(output, value, transportKey, envelope.deviceId)
        }
        when (envelope.type) {
            RemoteMessageType.PING -> {
                if (transportKey == null && PairingManager.getPhoneSessionKey(envelope.deviceId) != null) {
                    writeReply(RemoteReply(requestId = envelope.requestId, accepted = false, error = "encrypted-transport-required"))
                    return
                }
                if (transportKey != null && authenticate(envelope) != AuthResult.OK) {
                    writeReply(RemoteReply(envelope.requestId, false, error = "unauthenticated"))
                    return
                }
                val paired = PairingManager.getPhoneToken(envelope.deviceId) != null
                writeReply(
                    RemoteReply(
                        requestId = envelope.requestId,
                        accepted = true,
                        payload = encodePayload(CommandHandlers.buildDeviceInfo(context, paired)),
                    )
                )
            }

            RemoteMessageType.PAIR_HELLO -> handlePairHello(context, output, envelope, source)
            RemoteMessageType.PAIR_VERIFY -> handlePairVerify(context, output, envelope, source)

            RemoteMessageType.SUBSCRIBE -> handleSubscribe(context, socket, output, envelope, transportKey)

            else -> {
                if (transportKey == null) {
                    writeReply(RemoteReply(envelope.requestId, false, error = "encrypted-transport-required"))
                    return
                }
                val auth = authenticate(envelope)
                if (auth != AuthResult.OK) {
                    val error = when (auth) {
                        AuthResult.CLOCK_SKEW -> "clock-skew"
                        AuthResult.NONCE_CACHE_FULL -> "rate-limited"
                        else -> "unauthenticated"
                    }
                    writeReply(RemoteReply(envelope.requestId, false, error = error))
                    return
                }
                if (!PairingManager.isControlAllowed(context)) {
                    writeReply(RemoteReply(envelope.requestId, false, error = "control-disabled"))
                    return
                }
                writeReply(CommandHandlers.handle(context, envelope))
                if (envelope.type == RemoteMessageType.UNPAIR) {
                    // Send the acknowledgement first, then revoke every other socket.
                    revokeDeviceSockets(envelope.deviceId)
                }
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
        source: String,
    ) {
        if (!PairingManager.allowPairingAttempt(source, verify = false)) {
            runCatching {
                LanRemoteProtocol.write(output, RemoteReply(envelope.requestId, false, error = "rate-limited"))
            }
            return
        }
        val hello = envelope.payloadAs<PairHelloRequest>()
        if (hello == null || hello.deviceId.isBlank() || hello.publicKey.isBlank()) {
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
        val session = runCatching {
            PairingManager.startPairingSession(hello.deviceId, hello.deviceName, hello.publicKey)
        }.getOrElse {
            runCatching {
                LanRemoteProtocol.write(
                    output,
                    RemoteReply(requestId = envelope.requestId, accepted = false, error = "invalid-pairing"),
                )
            }
            return
        }
        mainHandler.post {
            if (CommonActivity.activity == null) {
                launchApp(context)
            }
            PairingOverlayDialog.show(session)
        }
        runCatching {
            LanRemoteProtocol.write(
                output,
                RemoteReply(
                    requestId = envelope.requestId,
                    accepted = true,
                    payload = encodePayload(
                        PairHelloReply(
                            pairingSessionId = session.sessionId,
                            expiresInMs = 120_000,
                            publicKey = RemoteCrypto.publicKeyBase64(session.tvKeyPair),
                        )
                    ),
                ),
            )
        }
    }

    private fun handlePairVerify(
        context: Context,
        output: DataOutputStream,
        envelope: RemoteEnvelope,
        source: String,
    ) {
        if (!PairingManager.allowPairingAttempt(source, verify = true)) {
            runCatching {
                LanRemoteProtocol.write(output, RemoteReply(envelope.requestId, false, error = "rate-limited"))
            }
            return
        }
        if (!PairingManager.isPairingAllowed(context)) {
            runCatching {
                LanRemoteProtocol.write(output, RemoteReply(envelope.requestId, false, error = "pairing-disabled"))
            }
            return
        }
        val verify = envelope.payloadAs<PairVerifyRequest>()
        if (verify == null) {
            runCatching {
                LanRemoteProtocol.write(output, RemoteReply(envelope.requestId, false, error = "invalid-pairing"))
            }
            return
        }
        val session = PairingManager.getPairingSession(verify.pairingSessionId)
        if (session == null) {
            runCatching {
                LanRemoteProtocol.write(output, RemoteReply(envelope.requestId, false, error = "pairing-expired"))
            }
            return
        }
        val sessionKey = PairingManager.sessionKey(session)
        fun reply(value: RemoteReply) = runCatching {
            writeReply(output, value, sessionKey, session.phoneDeviceId)
        }
        if (envelope.deviceId != session.phoneDeviceId) {
            reply(RemoteReply(envelope.requestId, false, error = "device-mismatch"))
            return
        }
        // The pairing window may be closed after the session was created.
        if (!PairingManager.isPairingAllowed(context)) {
            reply(RemoteReply(envelope.requestId, false, error = "pairing-disabled"))
            return
        }
        when (PairingManager.verifyPinProof(session, verify.proof)) {
            PairingManager.PinResult.OK -> {
                val token = RemoteAuth.newToken()
                PairingManager.registerPhone(
                    PairedPhone(
                        deviceId = session.phoneDeviceId,
                        name = session.phoneName,
                        token = token,
                        sessionKey = RemoteAuth.encodeBase64(sessionKey),
                    )
                )
                mainHandler.post { PairingOverlayDialog.dismiss() }
                reply(
                    RemoteReply(
                        requestId = envelope.requestId,
                        accepted = true,
                        payload = encodePayload(
                            PairVerifyReply(token, CommandHandlers.buildDeviceInfo(context, paired = true))
                        ),
                    )
                )
            }
            PairingManager.PinResult.WRONG -> reply(RemoteReply(envelope.requestId, false, error = "invalid-pin"))
            PairingManager.PinResult.EXPIRED -> reply(RemoteReply(envelope.requestId, false, error = "pairing-expired"))
            PairingManager.PinResult.BLOCKED -> reply(RemoteReply(envelope.requestId, false, error = "pairing-blocked"))
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
        transportKey: ByteArray?,
    ) {
        if (transportKey == null) {
            runCatching {
                LanRemoteProtocol.write(output, RemoteReply(envelope.requestId, false, error = "encrypted-transport-required"))
            }
            return
        }
        val auth = authenticate(envelope)
        if (auth != AuthResult.OK) {
            runCatching {
                writeReply(
                    output,
                    RemoteReply(requestId = envelope.requestId, accepted = false, error = "unauthenticated"),
                    transportKey,
                    envelope.deviceId,
                )
            }
            return
        }
        if (!PairingManager.isControlAllowed(context)) return
        runCatching {
            // Frame-atomicity: broadcast writes use the same socket-level lock (NowPlayingHub.writeEvent).
            synchronized(socket) {
                writeReply(
                    output,
                    RemoteReply(requestId = envelope.requestId, accepted = true),
                    transportKey,
                    envelope.deviceId,
                )
            }
        }
        val capabilities = envelope.payloadAs<SubscribePayload>()?.capabilities ?: emptySet()
        NowPlayingHub.registerSubscriber(envelope.deviceId, socket, capabilities, transportKey)

        // Hold the socket open; NowPlayingHub streams events. This is intentionally an
        // indefinite read: a subscribed phone may receive no frames while playback is idle,
        // and timing it out makes the now-playing bar disappear even though both devices are
        // still healthy. EOF/socket close remains the disconnect signal.
        try {
            socket.soTimeout = 0
            val input = DataInputStream(socket.getInputStream())
            var readBytes = 0
            while (true) {
                val b = input.read()
                if (b == -1) break
                readBytes++
                if (readBytes > MAX_SUBSCRIBE_READ_BYTES) break
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
            version = envelope.version,
            deviceId = envelope.deviceId,
            requestId = envelope.requestId,
            timestampMs = envelope.timestampMs,
            type = envelope.type,
            payload = envelope.payload,
            nowMs = System.currentTimeMillis(),
            seenNonces = PairingManager.replaySetFor(envelope.deviceId),
        )
    }

    private fun writeReply(
        output: DataOutputStream,
        reply: RemoteReply,
        transportKey: ByteArray?,
        deviceId: String,
    ) {
        val element = LanRemoteProtocol.json.parseToJsonElement(
            LanRemoteProtocol.json.encodeToString(reply)
        )
        if (transportKey == null) {
            LanRemoteProtocol.writeJson(output, element)
        } else {
            LanRemoteProtocol.writeEncrypted(output, transportKey, deviceId, element)
        }
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

    private fun registerService(context: Context, serviceGeneration: Long) {
        synchronized(lifecycleLock) {
            if (!started.get() || generation != serviceGeneration || registrationListener != null) return
        }
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        synchronized(lifecycleLock) {
            if (!started.get() || generation != serviceGeneration) return
            nsdManager = manager
            registrationListener = listener
        }
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

    private fun unregisterService() {
        val (manager, listener) = synchronized(lifecycleLock) {
            val result = nsdManager to registrationListener
            nsdManager = null
            registrationListener = null
            result
        }
        if (listener != null) runCatching { manager?.unregisterService(listener) }
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
}
