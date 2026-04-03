package com.wspulse.client.component

import com.wspulse.client.AutoReconnectConfig
import com.wspulse.client.Client
import com.wspulse.client.ClientConfig
import com.wspulse.client.HeartbeatConfig
import com.wspulse.client.WspulseClient
import com.wspulse.client.WspulseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Callback behavior component tests for [WspulseClient].
 *
 * Uses [MockTransport] and [MockDialer] to eliminate network I/O.
 */
class CallbackTest {
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

    // ── Scenario 2: transport drop without reconnect ────────────────────────

    @Test
    fun `transport error fires onTransportDrop and onDisconnect without reconnect`() =
        kotlinx.coroutines.test.runTest {
            val transportDropErr = AtomicReference<Exception?>(null)
            val transportDropped = CountDownLatch(1)
            val disconnectErr = AtomicReference<WspulseException?>(null)
            val disconnectCalled = CountDownLatch(1)

            val transport = MockTransport()
            val pongResponder = transport.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onTransportDrop = { err ->
                            transportDropErr.set(err)
                            transportDropped.countDown()
                        }
                        onDisconnect = { err ->
                            disconnectErr.set(err)
                            disconnectCalled.countDown()
                        }
                    },
                    dialer,
                )
            testClient = client

            // Respond to initial ping.
            waitForPing(transport)
            pongResponder.tick()

            // Simulate transport drop.
            transport.injectClose()

            // Both callbacks should fire.
            assertTrue(transportDropped.await(5, TimeUnit.SECONDS))
            assertTrue(disconnectCalled.await(5, TimeUnit.SECONDS))

            assertTrue(
                transportDropErr.get() != null,
                "onTransportDrop should receive a non-null error",
            )
            assertTrue(
                disconnectErr.get() != null,
                "onDisconnect should receive a non-null error",
            )
        }

    // ── onDisconnect fires exactly once on close ────────────────────────────

    @Test
    fun `onDisconnect fires exactly once on close`() =
        kotlinx.coroutines.test.runTest {
            val disconnectCount = AtomicInteger(0)

            val transport = MockTransport()
            val pongResponder = transport.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onDisconnect = { disconnectCount.incrementAndGet() }
                    },
                    dialer,
                )
            testClient = client

            waitForPing(transport)
            pongResponder.tick()

            client.close()
            client.done.await()

            // Brief window for any erroneous second call.
            withContext(Dispatchers.Default) { delay(200) }

            assertEquals(1, disconnectCount.get())
        }

    // ── onTransportRestore does not fire on initial connect ─────────────────

    @Test
    fun `onTransportRestore does not fire on initial connect`() =
        kotlinx.coroutines.test.runTest {
            val restoreFired =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)

            val transport = MockTransport()
            val pongResponder = transport.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onTransportRestore = { restoreFired.set(true) }
                    },
                    dialer,
                )
            testClient = client

            waitForPing(transport)
            pongResponder.tick()

            // Give time for any erroneous callback.
            withContext(Dispatchers.Default) { delay(500) }

            assertFalse(
                restoreFired.get(),
                "onTransportRestore must not fire on initial connect",
            )
        }

    // ── close from onTransportDrop suppresses onTransportRestore ────────────

    @Test
    fun `close from onTransportDrop suppresses onTransportRestore`() =
        kotlinx.coroutines.test.runTest {
            val restoreCount = AtomicInteger(0)
            val disconnectCalled = CountDownLatch(1)
            val disconnectErr = AtomicReference<WspulseException?>(null)
            val transportDropped = CountDownLatch(1)

            val transport1 = MockTransport()
            val transport2 = MockTransport()
            val pongResponder1 = transport1.autoPong()
            val dialer =
                MockDialer(listOf(Result.success(transport1), Result.success(transport2)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onTransportDrop = { transportDropped.countDown() }
                        onTransportRestore = { restoreCount.incrementAndGet() }
                        onDisconnect = { err ->
                            disconnectErr.set(err)
                            disconnectCalled.countDown()
                        }
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

            waitForPing(transport1)
            pongResponder1.tick()

            // Drop the transport.
            transport1.injectClose()

            // Wait for transport drop, then close immediately.
            assertTrue(transportDropped.await(5, TimeUnit.SECONDS))
            client.close()

            assertTrue(disconnectCalled.await(10, TimeUnit.SECONDS))

            // Brief window for any erroneous onTransportRestore call.
            withContext(Dispatchers.Default) { delay(500) }

            assertEquals(0, restoreCount.get(), "onTransportRestore must not fire after close()")
            assertNull(disconnectErr.get(), "close() should produce null disconnect error")
        }

    // ── Transport error fires onTransportDrop ───────────────────────────────

    @Test
    fun `transport error with exception fires onTransportDrop with that error`() =
        kotlinx.coroutines.test.runTest {
            val transportDropErr = AtomicReference<Exception?>(null)
            val transportDropped = CountDownLatch(1)
            val disconnectCalled = CountDownLatch(1)

            val transport = MockTransport()
            val pongResponder = transport.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onTransportDrop = { err ->
                            transportDropErr.set(err)
                            transportDropped.countDown()
                        }
                        onDisconnect = { disconnectCalled.countDown() }
                    },
                    dialer,
                )
            testClient = client

            waitForPing(transport)
            pongResponder.tick()

            // Inject a transport error.
            transport.injectError(Exception("connection reset"))

            assertTrue(transportDropped.await(5, TimeUnit.SECONDS))
            assertTrue(disconnectCalled.await(5, TimeUnit.SECONDS))
            assertTrue(
                transportDropErr.get()?.message?.contains("connection reset") == true,
                "onTransportDrop should propagate the error",
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
