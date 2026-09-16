package com.lagradost.cloudstream3.companion.transport

import com.lagradost.cloudstream3.companion.protocol.FrameCodec
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CompanionEndpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be between 1 and 65535" }
    }

    fun socketAddress(): InetSocketAddress = InetSocketAddress(host, port)

    companion object {
        fun parse(value: String, defaultPort: Int = CompanionTransport.DEFAULT_PORT): CompanionEndpoint {
            val input = value.trim()
            require(input.isNotEmpty()) { "address must not be blank" }
            require(defaultPort in 1..65535) { "default port must be between 1 and 65535" }

            if (input.startsWith("[")) {
                val closing = input.indexOf(']')
                require(closing > 1) { "invalid bracketed address" }
                val host = input.substring(1, closing)
                val suffix = input.substring(closing + 1)
                val port = if (suffix.isEmpty()) defaultPort else {
                    require(suffix.startsWith(":")) { "invalid bracketed address" }
                    parsePort(suffix.substring(1))
                }
                return CompanionEndpoint(host, port)
            }

            val colonCount = input.count { it == ':' }
            if (colonCount == 0) return CompanionEndpoint(input, defaultPort)
            if (colonCount > 1) return CompanionEndpoint(input, defaultPort)

            val separator = input.lastIndexOf(':')
            val host = input.substring(0, separator)
            require(host.isNotBlank()) { "host must not be blank" }
            return CompanionEndpoint(host, parsePort(input.substring(separator + 1)))
        }

        private fun parsePort(value: String): Int {
            val port = value.toIntOrNull() ?: throw IllegalArgumentException(
                "port must be between 1 and 65535"
            )
            require(port in 1..65535) { "port must be between 1 and 65535" }
            return port
        }
    }
}

data class CompanionTransportLimits(
    val maxConnections: Int = 8,
    val maxConnectionsPerAddress: Int = 3,
    val handshakeTimeoutMs: Int = 10_000,
    val idleTimeoutMs: Int = 5 * 60_000,
) {
    init {
        require(maxConnections > 0)
        require(maxConnectionsPerAddress > 0)
        require(handshakeTimeoutMs > 0)
        require(idleTimeoutMs > 0)
    }
}

interface CompanionAuthenticator {
    suspend fun authenticate(socket: Socket, deadlineMs: Int): String
}

fun interface CompanionConnectionHandler {
    suspend fun handle(connection: CompanionConnection)
}

class CompanionConnection internal constructor(
    val socket: Socket,
    val deviceId: String,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()

    fun readFrame(): ByteArray {
        check(!closed.get()) { "connection is closed" }
        return FrameCodec.readFrame(socket.getInputStream())
    }

    fun writeFrame(payload: ByteArray) {
        check(!closed.get()) { "connection is closed" }
        synchronized(writeLock) {
            FrameCodec.writeFrame(socket.getOutputStream(), payload)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }
}

class CompanionTransport private constructor() {
    companion object {
        const val DEFAULT_PORT = 46_899
    }
}

class CompanionTcpServer(
    private val scope: CoroutineScope,
    private val authenticator: CompanionAuthenticator,
    private val handler: CompanionConnectionHandler,
    private val limits: CompanionTransportLimits = CompanionTransportLimits(),
    private val bindAddress: InetAddress? = null,
    private val serverSocketFactory: () -> ServerSocket = ::ServerSocket,
) : Closeable {
    private val lifecycleMutex = Mutex()
    // Collections.newSetFromMap (not ConcurrentHashMap.newKeySet) because newKeySet
    // requires API 24 and minSdk is 23; semantics are the same concurrent set.
    private val liveSockets =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    private val addressCounts = ConcurrentHashMap<String, Int>()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    @Volatile
    private var generation = 0L
    private var boundPort = 0

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    val port: Int
        get() = boundPort

    val activeConnectionCount: Int
        get() = liveSockets.size

    suspend fun start(port: Int = CompanionTransport.DEFAULT_PORT): Int = lifecycleMutex.withLock {
        if (isRunning) return@withLock boundPort
        require(port in 0..65535) { "port must be between 0 and 65535" }
        val currentGeneration = ++generation
        val socket = serverSocketFactory()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(bindAddress, port))
        } catch (error: Exception) {
            socket.close()
            throw error
        }
        if (generation != currentGeneration) {
            socket.close()
            throw CancellationException("companion server start superseded")
        }
        serverSocket = socket
        boundPort = socket.localPort
        acceptJob = scope.launch(Dispatchers.IO) {
            acceptLoop(socket, currentGeneration)
        }
        boundPort
    }

    suspend fun stop() {
        val acceptJobToJoin = lifecycleMutex.withLock {
            ++generation
            val socket = serverSocket
            serverSocket = null
            boundPort = 0
            socket?.close()
            val sockets = synchronized(quotaLock) {
                liveSockets.toList().also {
                    liveSockets.clear()
                    addressCounts.clear()
                }
            }
            sockets.forEach(Socket::close)
            acceptJob?.also { it.cancel() }.also { acceptJob = null }
        }
        acceptJobToJoin?.cancelAndJoin()
    }

    override fun close() = runBlocking { stop() }

    private suspend fun acceptLoop(socket: ServerSocket, loopGeneration: Long) {
        try {
            while (socket.isClosed.not()) {
                currentCoroutineContext().ensureActive()
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                } catch (_: IOException) {
                    break
                }
                if (generation != loopGeneration || !reserve(client)) {
                    client.close()
                    continue
                }
                scope.launch(Dispatchers.IO) {
                    service(client, loopGeneration)
                }
            }
        } finally {
            if (generation == loopGeneration) {
                lifecycleMutex.withLock {
                    if (generation == loopGeneration && serverSocket === socket) {
                        serverSocket = null
                        boundPort = 0
                    }
                }
            }
        }
    }

    private fun reserve(socket: Socket): Boolean {
        val address = remoteAddress(socket)
        synchronized(quotaLock) {
            val count = addressCounts[address] ?: 0
            if (liveSockets.size >= limits.maxConnections || count >= limits.maxConnectionsPerAddress) {
                return false
            }
            liveSockets.add(socket)
            addressCounts[address] = count + 1
            return true
        }
    }

    private suspend fun service(socket: Socket, loopGeneration: Long) {
        val address = remoteAddress(socket)
        try {
            if (generation != loopGeneration) return
            socket.soTimeout = limits.handshakeTimeoutMs
            val deviceId = authenticator.authenticate(socket, limits.handshakeTimeoutMs)
            if (generation != loopGeneration) return
            // Read-idle kill applies to every accepted socket, including
            // event-subscribed ones: subscribed channels stay alive via the phone
            // keepalive PING (PhoneCompanionSession KEEPALIVE_PING_INTERVAL_MS
            // is shorter than idleTimeoutMs).
            socket.soTimeout = limits.idleTimeoutMs
            handler.handle(CompanionConnection(socket, deviceId))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A malformed handshake, timeout, or handler failure closes only this connection.
        } finally {
            synchronized(quotaLock) {
                liveSockets.remove(socket)
                if (generation == loopGeneration) {
                    addressCounts.computeIfPresent(address) { _, old ->
                        if (old <= 1) null else old - 1
                    }
                }
            }
            socket.close()
        }
    }

    private fun remoteAddress(socket: Socket): String =
        socket.inetAddress?.hostAddress?.lowercase() ?: "unknown"

    private val quotaLock = Any()
}

class CompanionTcpClient(
    private val scope: CoroutineScope,
    private val authenticator: CompanionAuthenticator,
    private val handler: CompanionConnectionHandler,
    private val limits: CompanionTransportLimits = CompanionTransportLimits(),
    private val socketFactory: () -> Socket = ::Socket,
) : Closeable {
    private val lifecycleMutex = Mutex()
    private var connection: CompanionConnection? = null
    private var connectionJob: Job? = null
    @Volatile
    private var generation = 0L

    suspend fun connect(endpoint: CompanionEndpoint): CompanionConnection = lifecycleMutex.withLock {
        connection?.let { return@withLock it }
        val currentGeneration = ++generation
        val socket = socketFactory()
        try {
            socket.connect(endpoint.socketAddress(), limits.handshakeTimeoutMs)
            socket.soTimeout = limits.handshakeTimeoutMs
            val deviceId = authenticator.authenticate(socket, limits.handshakeTimeoutMs)
            socket.soTimeout = limits.idleTimeoutMs
            val connected = CompanionConnection(socket, deviceId)
            connection = connected
            connectionJob = scope.launch(Dispatchers.IO) {
                try {
                    handler.handle(connected)
                } finally {
                    lifecycleMutex.withLock {
                        if (generation == currentGeneration && connection === connected) {
                            connection = null
                            connectionJob = null
                        }
                    }
                    connected.close()
                }
            }
            connected
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    suspend fun disconnect() {
        val jobToJoin = lifecycleMutex.withLock {
            ++generation
            connection?.close()
            connection = null
            connectionJob?.also { it.cancel() }.also { connectionJob = null }
        }
        jobToJoin?.cancelAndJoin()
    }

    override fun close() = runBlocking { disconnect() }
}
