package com.lagradost.cloudstream3.companion.transport

import java.io.IOException
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionTransportTest {
    @Test
    fun `nsd records contain the companion version and identity fields`() {
        val attributes = CompanionNsdRecords.attributes("Living Room TV", "abc123")
        assertEquals("3", attributes[CompanionNsdRecords.TXT_VERSION]?.decodeToString())
        assertEquals("Living Room TV", attributes[CompanionNsdRecords.TXT_NAME]?.decodeToString())
        assertEquals("abc123", attributes[CompanionNsdRecords.TXT_FINGERPRINT]?.decodeToString())
    }

    @Test
    fun `address parser handles hostname ipv4 and ipv6 forms`() {
        assertEquals(CompanionEndpoint("tv.local", 46_899), CompanionEndpoint.parse("tv.local"))
        assertEquals(CompanionEndpoint("192.168.1.2", 4_321), CompanionEndpoint.parse("192.168.1.2:4321"))
        assertEquals(CompanionEndpoint("2001:db8::1", 4_321), CompanionEndpoint.parse("[2001:db8::1]:4321"))
        assertEquals(CompanionEndpoint("2001:db8::1", 46_899), CompanionEndpoint.parse("2001:db8::1"))
    }

    @Test
    fun `address parser rejects malformed or out of range ports`() {
        listOf("tv.local:0", "tv.local:65536", "[2001:db8::1]x", "[]:1234").forEach { value ->
            try {
                CompanionEndpoint.parse(value)
                throw AssertionError("expected parse failure for $value")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `server accepts a connection and applies framing`() = runBlocking {
        val received = CompletableDeferred<ByteArray>()
        val server = CompanionTcpServer(
            scope = this,
            authenticator = object : CompanionAuthenticator {
                override suspend fun authenticate(socket: java.net.Socket, deadlineMs: Int): String =
                    "test-device"
            },
            handler = CompanionConnectionHandler { connection ->
                received.complete(connection.readFrame())
            },
        )
        try {
            val port = server.start(0)
            java.net.Socket("127.0.0.1", port).use { socket ->
                com.lagradost.cloudstream3.companion.protocol.FrameCodec.writeFrame(
                    socket.getOutputStream(),
                    byteArrayOf(1, 2, 3),
                )
            }
            assertEquals(listOf<Byte>(1, 2, 3), received.await().toList())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `server quota admits at most two concurrent connections from one address`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val server = CompanionTcpServer(
            scope = this,
            authenticator = object : CompanionAuthenticator {
                override suspend fun authenticate(socket: Socket, deadlineMs: Int): String =
                    "test-device"
            },
            handler = CompanionConnectionHandler { release.await() },
            limits = CompanionTransportLimits(maxConnections = 8, maxConnectionsPerAddress = 2),
        )
        val sockets = mutableListOf<Socket>()
        try {
            val port = server.start(0)
            sockets += (1..5).map {
                async(Dispatchers.IO) { Socket("127.0.0.1", port) }
            }.awaitAll()
            withTimeout(2_000) {
                while (server.activeConnectionCount < 2) yield()
            }
            assertEquals(2, server.activeConnectionCount)
        } finally {
            sockets.forEach(Socket::close)
            release.complete(Unit)
            server.stop()
        }
    }

    @Test
    fun `stop closes the listener before returning`() = runBlocking {
        val server = CompanionTcpServer(
            scope = this,
            authenticator = object : CompanionAuthenticator {
                override suspend fun authenticate(socket: Socket, deadlineMs: Int): String =
                    "test-device"
            },
            handler = CompanionConnectionHandler { },
        )
        val port = server.start(0)
        server.stop()
        assertEquals(0, server.port)
        assertTrue(!server.isRunning)
        try {
            Socket("127.0.0.1", port).use {
                throw AssertionError("listener still accepted connections after stop")
            }
        } catch (_: IOException) {
            // Expected: the listener was closed before stop returned.
        }
    }

    @Test
    fun `client disconnect cancels its owning handler`() = runBlocking {
        val serverEntered = CompletableDeferred<Unit>()
        val clientCancelled = CompletableDeferred<Unit>()
        val serverRelease = CompletableDeferred<Unit>()
        val server = CompanionTcpServer(
            scope = this,
            authenticator = object : CompanionAuthenticator {
                override suspend fun authenticate(socket: Socket, deadlineMs: Int): String =
                    "test-device"
            },
            handler = CompanionConnectionHandler {
                serverEntered.complete(Unit)
                serverRelease.await()
            },
        )
        val client = CompanionTcpClient(
            scope = this,
            authenticator = object : CompanionAuthenticator {
                override suspend fun authenticate(socket: Socket, deadlineMs: Int): String =
                    "test-device"
            },
            handler = CompanionConnectionHandler {
                try {
                    awaitCancellation()
                } finally {
                    clientCancelled.complete(Unit)
                }
            },
        )
        try {
            val port = server.start(0)
            client.connect(CompanionEndpoint("127.0.0.1", port))
            serverEntered.await()
            client.disconnect()
            clientCancelled.await()
        } finally {
            client.disconnect()
            serverRelease.complete(Unit)
            server.stop()
        }
    }

    @Test
    fun `reconnect loop uses bounded exponential delays and is single instance`() = runBlocking {
        val attempts = CopyOnWriteArrayList<Int>()
        val sleeps = CopyOnWriteArrayList<Long>()
        val loop = CompanionReconnectLoop(
            scope = this,
            connect = {
                val attempt = attempts.size
                attempts.add(attempt)
                if (attempt < 4) error("offline")
                throw kotlinx.coroutines.CancellationException("stop test")
            },
            sleep = { sleeps += it },
        )
        loop.start()
        loop.start()
        withTimeout(2_000) {
            while (attempts.size < 4) kotlinx.coroutines.yield()
        }
        loop.stop()
        assertTrue(attempts.size >= 4)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), sleeps.take(3))
    }
}
