package com.wspulse.client.component

import com.wspulse.client.AutoReconnectConfig
import com.wspulse.client.Client
import com.wspulse.client.ClientConfig
import com.wspulse.client.HeartbeatConfig
import com.wspulse.client.TransportFrame
import com.wspulse.client.WspulseClient
import com.wspulse.client.WspulseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
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
    fun `transport drop fires onTransportDrop and onDisconnect without reconnect`() =
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
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
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
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            testClient = client

            waitForPing(transport)
            pongResponder.tick()

            client.close()
            client.done.await()

            // Brief window for any erroneous second call.
            testScheduler.advanceTimeBy(200)

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
                    clientConfig { onTransportRestore = { restoreFired.set(true) } },
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            testClient = client

            waitForPing(transport)
            pongResponder.tick()

            // Give time for any erroneous callback.
            testScheduler.advanceTimeBy(500)

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
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
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
            testScheduler.advanceTimeBy(500)

            assertEquals(
                0,
                restoreCount.get(),
                "onTransportRestore must not fire after close()",
            )
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
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
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

    // ── Clean close fires onTransportDrop(null) before onDisconnect(null) ───

    @Test
    fun `clean close fires onTransportDrop null before onDisconnect null`() =
        kotlinx.coroutines.test.runTest {
            val order = CopyOnWriteArrayList<String>()
            val disconnectCalled = CountDownLatch(1)

            val transport = MockTransport()
            val pongResponder = transport.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onTransportDrop = { err ->
                            assertNull(
                                err,
                                "onTransportDrop err should be null on clean close",
                            )
                            order.add("onTransportDrop")
                        }
                        onDisconnect = { err ->
                            assertNull(
                                err,
                                "onDisconnect err should be null on clean close",
                            )
                            order.add("onDisconnect")
                            disconnectCalled.countDown()
                        }
                    },
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            testClient = client

            waitForPing(transport)
            pongResponder.tick()

            client.close()
            client.done.await()

            assertTrue(disconnectCalled.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("onTransportDrop", "onDisconnect"), order)
        }

    // ── onTransportDrop callback safety ────────────────────────────────────

    @Test
    fun `throwing onTransportDrop does not prevent onDisconnect from firing`() =
        kotlinx.coroutines.test.runTest {
            val disconnectCalled = CountDownLatch(1)

            val transport = MockTransport()
            val pongResponder = transport.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onTransportDrop = { throw RuntimeException("callback boom") }
                        onDisconnect = { disconnectCalled.countDown() }
                    },
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            testClient = client

            waitForPing(transport)
            pongResponder.tick()

            transport.injectClose()

            assertTrue(
                disconnectCalled.await(5, TimeUnit.SECONDS),
                "onDisconnect must fire even when onTransportDrop throws",
            )
        }

    @Test
    fun `throwing onTransportDrop in reconnect loop does not abort reconnect`() =
        kotlinx.coroutines.test.runTest {
            val restoreCalled = CountDownLatch(1)
            val disconnectCalled = CountDownLatch(1)

            val transport1 = MockTransport()
            val transport2 = MockTransport()
            val transport3 = MockTransport()
            val pongResponder1 = transport1.autoPong()
            val pongResponder2 = transport2.autoPong()
            transport3.autoPong()
            val dialer =
                MockDialer(
                    listOf(
                        Result.success(transport1),
                        Result.success(transport2),
                        Result.success(transport3),
                    ),
                )

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onTransportDrop = { throw RuntimeException("callback boom") }
                        onTransportRestore = { restoreCalled.countDown() }
                        onDisconnect = { disconnectCalled.countDown() }
                        autoReconnect =
                            AutoReconnectConfig(
                                maxRetries = 3,
                                baseDelay = 1.milliseconds,
                                maxDelay = 5.milliseconds,
                            )
                    },
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            testClient = client

            // First connection established.
            waitForPing(transport1)
            pongResponder1.tick()

            // Drop first transport — triggers handleTransportDrop (line 474).
            transport1.injectClose()

            // Reconnect loop should recover and connect with transport2.
            testScheduler.advanceTimeBy(20)
            waitForPing(transport2)
            pongResponder2.tick()

            assertTrue(
                restoreCalled.await(5, TimeUnit.SECONDS),
                "onTransportRestore must fire after successful reconnect",
            )

            // Drop second transport — triggers reconnectLoop onTransportDrop (line 561).
            transport2.injectClose()

            // Reconnect loop should continue (not abort) despite throwing onTransportDrop.
            testScheduler.advanceTimeBy(20)
            waitForPing(transport3)
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
        if (condition()) return // fast path: immediately satisfied (UnconfinedTestDispatcher)
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMs.milliseconds) {
                while (!condition()) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
    }

    private suspend fun waitForPing(transport: MockTransport) {
        waitUntil { transport.sent.any { it is TransportFrame.Ping } }
    }
}
