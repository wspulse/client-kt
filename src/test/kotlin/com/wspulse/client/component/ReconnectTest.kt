package com.wspulse.client.component

import com.wspulse.client.AutoReconnectConfig
import com.wspulse.client.Client
import com.wspulse.client.ClientConfig
import com.wspulse.client.Dialer
import com.wspulse.client.Frame
import com.wspulse.client.HeartbeatConfig
import com.wspulse.client.RetriesExhaustedException
import com.wspulse.client.Transport
import com.wspulse.client.TransportFrame
import com.wspulse.client.WspulseClient
import com.wspulse.client.WspulseException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Reconnect flow component tests for [WspulseClient].
 *
 * Uses [MockTransport] and [MockDialer] to eliminate network I/O.
 */
class ReconnectTest {
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

    // ── Scenario 3: auto-reconnect after transport drop ─────────────────────

    @Test
    fun `reconnects after transport drop and resumes message flow`() =
        kotlinx.coroutines.test.runTest {
            val received = CopyOnWriteArrayList<Frame>()
            val transportRestored = CountDownLatch(1)

            val transport1 = MockTransport()
            val transport2 = MockTransport()
            val pongResponder1 = transport1.autoPong()
            val pongResponder2 = transport2.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport1), Result.success(transport2)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onMessage = { frame -> received.add(frame) }
                        onTransportRestore = { transportRestored.countDown() }
                        autoReconnect =
                            AutoReconnectConfig(
                                maxRetries = 5,
                                baseDelay = 1.milliseconds,
                                maxDelay = 10.milliseconds,
                            )
                    },
                    dialer,
                )
            testClient = client

            // Respond to initial ping on transport1.
            waitForPing(transport1)
            pongResponder1.tick()

            // Send a frame before drop.
            client.send(Frame(event = "before", payload = "drop"))
            waitUntil { transport1.sent.any { it is TransportFrame.Text } }
            transport1.injectText("""{"event":"before","payload":"drop"}""")
            waitUntil { received.any { it.event == "before" } }

            // Drop transport1.
            transport1.injectClose()

            // Auto-pong on transport2 after reconnect.
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    while (dialer.dialCount < 2) {
                        pongResponder1.tick()
                        delay(10)
                    }
                }
            }

            // Wait for reconnect and respond to pings on transport2.
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    while (!transportRestored.await(10, TimeUnit.MILLISECONDS)) {
                        pongResponder2.tick()
                        delay(10)
                    }
                }
            }

            // Send after reconnect.
            client.send(Frame(event = "after", payload = "reconnect"))
            waitUntil { transport2.sent.any { it is TransportFrame.Text } }
            transport2.injectText("""{"event":"after","payload":"reconnect"}""")
            waitUntil { received.any { it.event == "after" } }
        }

    // ── Scenario 4: max retries exhausted ───────────────────────────────────

    @Test
    fun `fires RetriesExhaustedException after max retries exhausted`() =
        kotlinx.coroutines.test.runTest {
            val disconnectErr = AtomicReference<WspulseException?>(null)
            val disconnectCalled = CountDownLatch(1)

            val transport = MockTransport()
            val pongResponder = transport.autoPong()

            // Initial transport succeeds, all reconnect dials fail.
            val dialer =
                MockDialer(
                    listOf(
                        Result.success(transport),
                        Result.failure(Exception("dial failed")),
                        Result.failure(Exception("dial failed")),
                    ),
                )

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onDisconnect = { err ->
                            disconnectErr.set(err)
                            disconnectCalled.countDown()
                        }
                        autoReconnect =
                            AutoReconnectConfig(
                                maxRetries = 2,
                                baseDelay = 1.milliseconds,
                                maxDelay = 10.milliseconds,
                            )
                    },
                    dialer,
                )
            testClient = client

            // Respond to initial ping.
            waitForPing(transport)
            pongResponder.tick()

            // Drop the transport to start reconnect loop.
            transport.injectClose()

            // Wait for retries to exhaust.
            assertTrue(disconnectCalled.await(10, TimeUnit.SECONDS))

            assertTrue(
                disconnectErr.get() is RetriesExhaustedException,
                "expected RetriesExhaustedException but got: ${disconnectErr.get()}",
            )
        }

    // ── Scenario 5: close during reconnect ──────────────────────────────────

    @Test
    fun `close during reconnect fires onDisconnect null`() =
        kotlinx.coroutines.test.runTest {
            val disconnectErr = AtomicReference<WspulseException?>(null)
            val disconnectCalled = CountDownLatch(1)
            val transportDropSignal = CompletableDeferred<Unit>()

            val transport = MockTransport()
            val pongResponder = transport.autoPong()

            // Initial transport succeeds, reconnect dials fail (slow).
            val slowDialer =
                object : Dialer {
                    private val first = AtomicInteger(0)

                    override suspend fun dial(
                        url: String,
                        headers: Map<String, String>,
                    ): Transport {
                        if (first.getAndIncrement() == 0) {
                            return transport
                        }
                        // Simulate slow dial that will be cancelled.
                        delay(60.seconds)
                        throw Exception("should not reach here")
                    }
                }

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onDisconnect = { err ->
                            disconnectErr.set(err)
                            disconnectCalled.countDown()
                        }
                        onTransportDrop = { transportDropSignal.complete(Unit) }
                        autoReconnect =
                            AutoReconnectConfig(
                                maxRetries = 10,
                                baseDelay = 1.milliseconds,
                                maxDelay = 10.milliseconds,
                            )
                    },
                    slowDialer,
                )
            testClient = client

            // Respond to initial ping.
            waitForPing(transport)
            pongResponder.tick()

            // Drop to trigger reconnect.
            transport.injectClose()

            // Wait for transport drop callback.
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { transportDropSignal.await() }
            }

            // Close during reconnect.
            client.close()

            assertTrue(disconnectCalled.await(10, TimeUnit.SECONDS))
            assertNull(disconnectErr.get())
        }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Create a [ClientConfig] with long heartbeat to prevent timeout during tests. */
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
            transport.sent.any { it is TransportFrame.Ping }
        }
    }
}
