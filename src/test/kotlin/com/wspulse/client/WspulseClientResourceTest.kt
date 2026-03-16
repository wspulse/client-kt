package com.wspulse.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [WspulseClient] resource lifecycle.
 *
 * Uses [LocalWsServer] — a minimal WebSocket server built on [ServerSocket] —
 * to test transport-drop and reconnect scenarios without external dependencies.
 */
class WspulseClientResourceTest {

    // ── Issue #1: dial failure should not leak CIO resources ─────────────────

    @Test
    fun `connect failure without autoReconnect does not leak CIO resources`() {
        val server = LocalWsServer()
        try {
            val threadsBefore = ktorThreadCount()

            // Server rejects the upgrade (400) — CIO engine starts but connect fails.
            Thread { server.acceptAndRejectUpgrade() }.apply { isDaemon = true; start() }

            assertThrows<Exception> {
                runBlocking {
                    WspulseClient.connect("ws://127.0.0.1:${server.port}")
                }
            }

            // Wait for CIO engine threads to terminate.
            waitForThreads(threadsBefore)
        } finally {
            server.close()
        }
    }

    // ── Issue #2: transport-drop shutdown should cancel scope ────────────────

    @Test
    fun `transport drop without autoReconnect cleans up all resources`() = runBlocking {
        val server = LocalWsServer()
        try {
            val threadsBefore = ktorThreadCount()
            val disconnectLatch = CountDownLatch(1)

            // Start accepting in background.
            launch(Dispatchers.IO) { server.acceptAndHandshake() }

            val client = WspulseClient.connect("ws://127.0.0.1:${server.port}") {
                onDisconnect = { disconnectLatch.countDown() }
                // Long heartbeat to avoid interference.
                heartbeat = HeartbeatConfig(pingPeriod = 50.seconds, pongWait = 50.seconds)
            }

            // Give read/write/ping loops time to start.
            delay(200)

            // Server drops the connection.
            server.dropConnection()

            // Client should transition to CLOSED via shutdown().
            assertTrue(disconnectLatch.await(5, TimeUnit.SECONDS), "onDisconnect should fire")
            client.done.await()

            // CIO threads should be cleaned up.
            waitForThreads(threadsBefore)
        } finally {
            server.close()
        }
    }

    // ── Issue #3: reconnect-after-reconnect should work ─────────────────────

    @Test
    fun `reconnects again when new connection also drops`() = runBlocking {
        val server = LocalWsServer()
        try {
            val transportDropCount = AtomicInteger(0)
            val reconnectCount = AtomicInteger(0)
            val secondDropLatch = CountDownLatch(1)

            // Accept initial connection in background.
            launch(Dispatchers.IO) {
                try { server.acceptAndHandshake() } catch (_: Exception) {}
            }

            val client = WspulseClient.connect("ws://127.0.0.1:${server.port}") {
                autoReconnect = AutoReconnectConfig(
                    maxRetries = 10,
                    baseDelay = 0.1.seconds,
                    maxDelay = 0.5.seconds,
                )
                heartbeat = HeartbeatConfig(pingPeriod = 50.seconds, pongWait = 50.seconds)
                onTransportDrop = {
                    val count = transportDropCount.incrementAndGet()
                    if (count >= 2) secondDropLatch.countDown()
                }
                onReconnect = { reconnectCount.incrementAndGet() }
            }

            // Give loops time to start.
            delay(200)

            // 1st drop: server closes the initial connection.
            server.dropConnection()

            // Accept reconnect attempt.
            launch(Dispatchers.IO) {
                try { server.acceptAndHandshake() } catch (_: Exception) {}
            }
            // Wait for reconnect to complete.
            delay(2000)

            // 2nd drop: server closes the reconnected connection.
            server.dropConnection()

            // Accept another reconnect.
            launch(Dispatchers.IO) {
                try { server.acceptAndHandshake() } catch (_: Exception) {}
            }

            // Wait for the second transport drop to be handled.
            assertTrue(
                secondDropLatch.await(10, TimeUnit.SECONDS),
                "should handle 2 transport drops (got ${transportDropCount.get()})"
            )

            // Client should NOT be permanently closed.
            assertFalse(client.done.isCompleted, "client should still be alive after reconnects")

            // Clean up.
            client.close()
            client.done.await()
        } finally {
            server.close()
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun ktorThreadCount(): Int {
        return Thread.getAllStackTraces().keys.count { it.name.startsWith("ktor-") }
    }

    private fun waitForThreads(expectedMax: Int, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (ktorThreadCount() > expectedMax) {
            if (System.currentTimeMillis() > deadline) {
                val leaked = ktorThreadCount() - expectedMax
                throw AssertionError(
                    "ktor threads leaked: expected <= $expectedMax, got ${ktorThreadCount()} ($leaked leaked)"
                )
            }
            Thread.sleep(200)
        }
    }

    /**
     * Minimal WebSocket server for unit testing.
     *
     * Performs the HTTP/1.1 → WebSocket upgrade handshake on [acceptAndHandshake],
     * then allows controlled close via [dropConnection] to trigger transport drops.
     */
    private class LocalWsServer : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        val port: Int = serverSocket.localPort
        @Volatile
        private var clientSocket: java.net.Socket? = null

        /**
         * Accept one client connection and complete the WebSocket handshake.
         * Blocks until the handshake is done.
         */
        fun acceptAndHandshake() {
            val socket = serverSocket.accept()
            clientSocket = socket
            val wsKey = readUpgradeRequest(socket)
            sendUpgradeResponse(socket, wsKey)
        }

        /**
         * Accept one client connection and reject the upgrade with HTTP 400.
         * This forces Ktor to throw after establishing a TCP connection
         * (CIO engine resources are active).
         */
        fun acceptAndRejectUpgrade() {
            val socket = serverSocket.accept()
            readUpgradeRequest(socket)
            val response = "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(response.toByteArray())
            socket.getOutputStream().flush()
            socket.close()
        }

        /** Forcibly close the client socket to trigger a transport drop. */
        fun dropConnection() {
            clientSocket?.close()
            clientSocket = null
        }

        override fun close() {
            clientSocket?.close()
            serverSocket.close()
        }

        private fun readUpgradeRequest(socket: java.net.Socket): String {
            val reader = socket.getInputStream().bufferedReader()
            var wsKey = ""
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                    wsKey = line.substringAfter(":").trim()
                }
            }
            return wsKey
        }

        private fun sendUpgradeResponse(socket: java.net.Socket, wsKey: String) {
            val magic = "258EAFA5-E914-47DA-95CA-5AB5B3F93BE5"
            val sha1 = MessageDigest.getInstance("SHA-1")
            val acceptKey = Base64.getEncoder()
                .encodeToString(sha1.digest("$wsKey$magic".toByteArray()))

            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"
            socket.getOutputStream().write(response.toByteArray())
            socket.getOutputStream().flush()
        }
    }
}
