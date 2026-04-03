package com.wspulse.client.component

import com.wspulse.client.Client
import com.wspulse.client.ClientConfig
import com.wspulse.client.ConnectionLostException
import com.wspulse.client.Frame
import com.wspulse.client.HeartbeatConfig
import com.wspulse.client.WspulseClient
import com.wspulse.client.WspulseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Miscellaneous component tests for [WspulseClient].
 *
 * Uses [MockTransport] and [MockDialer] to eliminate network I/O.
 */
class MiscTest {
    private var testClient: Client? = null

    @AfterEach
    fun tearDown() {
        kotlinx.coroutines.runBlocking {
            testClient?.let {
                it.close()
                it.done.await()
            }
            testClient = null
        }
    }

    // ── Scenario 8: concurrent sends ────────────────────────────────────────

    @Test
    fun `concurrent sends from multiple coroutines do not race`() =
        kotlinx.coroutines.test.runTest {
            val received = CopyOnWriteArrayList<Frame>()

            val transport = MockTransport()
            val pongResponder = transport.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onMessage = { frame -> received.add(frame) }
                    },
                    dialer,
                )
            testClient = client

            waitForPing(transport)
            pongResponder.tick()

            val senders = 50
            val msgsPerSender = 5
            val total = senders * msgsPerSender

            // Launch concurrent senders.
            val jobs =
                (0 until senders).map { s ->
                    async {
                        for (m in 0 until msgsPerSender) {
                            client.send(
                                Frame(event = "concurrent", payload = mapOf("s" to s, "m" to m)),
                            )
                        }
                    }
                }
            jobs.awaitAll()

            // Wait for all writes to appear in the transport.
            waitUntil(timeoutMs = 10_000) {
                transport.sent.count { it is io.ktor.websocket.Frame.Text } >= total
            }

            // Inject echoes.
            val textFrames = transport.sent.filterIsInstance<io.ktor.websocket.Frame.Text>()
            for (f in textFrames) {
                transport.injectText(String(f.data, Charsets.UTF_8))
            }

            waitUntil(timeoutMs = 10_000) { received.size >= total }

            assertEquals(total, received.size)
            assertTrue(received.all { it.event == "concurrent" })
        }

    // ── Scenario 7: pong timeout ────────────────────────────────────────────

    @Test
    fun `pong timeout triggers ConnectionLostException`() =
        kotlinx.coroutines.test.runTest {
            val disconnectErr = AtomicReference<WspulseException?>(null)
            val disconnectCalled = CountDownLatch(1)

            val transport = MockTransport()
            // Do NOT auto-pong -- let the pong deadline fire.
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onDisconnect = { err ->
                            disconnectErr.set(err)
                            disconnectCalled.countDown()
                        }
                        heartbeat =
                            HeartbeatConfig(
                                pingPeriod = 100.milliseconds,
                                pongWait = 300.milliseconds,
                            )
                    },
                    dialer,
                )
            testClient = client

            // Wait for pong timeout -> transport close -> disconnect.
            assertTrue(disconnectCalled.await(10, TimeUnit.SECONDS))

            assertTrue(
                disconnectErr.get() is ConnectionLostException,
                "expected ConnectionLostException but got: ${disconnectErr.get()}",
            )
        }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun clientConfig(init: ClientConfig.() -> Unit = {}): ClientConfig =
        ClientConfig().apply {
            heartbeat = HeartbeatConfig(pingPeriod = 50.seconds, pongWait = 60.seconds)
            init()
        }

    private suspend fun waitUntil(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ) {
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMs.milliseconds) {
                while (!condition()) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
    }

    private suspend fun waitForPing(transport: MockTransport) {
        waitUntil {
            transport.sent.any { it is io.ktor.websocket.Frame.Ping }
        }
    }
}
