package com.wspulse.client.component

import com.wspulse.client.AutoReconnectConfig
import com.wspulse.client.WspulseClient
import com.wspulse.client.WspulseException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

/**
 * Callback behavior component tests for [WspulseClient].
 *
 * Uses [MockTransport] and [MockDialer] to eliminate network I/O.
 */
class CallbackTest : ComponentTestBase(TestCoroutineScheduler()) {
    // ── Scenario 2: transport drop without reconnect ────────────────────────

    @Test
    fun `transport drop fires onTransportDrop and onDisconnect without reconnect`() =
        runTest(StandardTestDispatcher(testScheduler)) {
            val transportDropErr = AtomicReference<Exception?>(null)
            val transportDropped = CompletableDeferred<Unit>()
            val disconnectErr = AtomicReference<WspulseException?>(null)
            val disconnectCalled = CompletableDeferred<Unit>()

            val transport = MockTransport()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onTransportDrop = { err ->
                            transportDropErr.set(err)
                            transportDropped.complete(Unit)
                        }
                        onDisconnect = { err ->
                            disconnectErr.set(err)
                            disconnectCalled.complete(Unit)
                        }
                    },
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            testClient = client

            // Simulate transport drop.
            transport.injectClose()

            // Both callbacks should fire.
            transportDropped.await()
            disconnectCalled.await()

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
        runTest(StandardTestDispatcher(testScheduler)) {
            val disconnectCount = AtomicInteger(0)

            val transport = MockTransport()
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

            client.close()
            client.done.await()

            // Brief window for any erroneous second call.
            testScheduler.advanceTimeBy(200)

            assertEquals(1, disconnectCount.get())
        }

    // ── onTransportRestore does not fire on initial connect ─────────────────

    @Test
    fun `onTransportRestore does not fire on initial connect`() =
        runTest(StandardTestDispatcher(testScheduler)) {
            val restoreFired =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)

            val transport = MockTransport()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig { onTransportRestore = { restoreFired.set(true) } },
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            testClient = client

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
        runTest(StandardTestDispatcher(testScheduler)) {
            val restoreCount = AtomicInteger(0)
            val disconnectCalled = CompletableDeferred<Unit>()
            val disconnectErr = AtomicReference<WspulseException?>(null)
            val transportDropped = CompletableDeferred<Unit>()

            val transport1 = MockTransport()
            val transport2 = MockTransport()
            val dialer =
                MockDialer(listOf(Result.success(transport1), Result.success(transport2)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onTransportDrop = { transportDropped.complete(Unit) }
                        onTransportRestore = { restoreCount.incrementAndGet() }
                        onDisconnect = { err ->
                            disconnectErr.set(err)
                            disconnectCalled.complete(Unit)
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

            // Drop the transport.
            transport1.injectClose()

            // Wait for transport drop, then close immediately.
            transportDropped.await()
            client.close()

            disconnectCalled.await()

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
        runTest(StandardTestDispatcher(testScheduler)) {
            val transportDropErr = AtomicReference<Exception?>(null)
            val transportDropped = CompletableDeferred<Unit>()
            val disconnectCalled = CompletableDeferred<Unit>()

            val transport = MockTransport()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onTransportDrop = { err ->
                            transportDropErr.set(err)
                            transportDropped.complete(Unit)
                        }
                        onDisconnect = { disconnectCalled.complete(Unit) }
                    },
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            testClient = client

            // Inject a transport error.
            transport.injectError(Exception("connection reset"))

            transportDropped.await()
            disconnectCalled.await()
            assertTrue(
                transportDropErr.get()?.message?.contains("connection reset") == true,
                "onTransportDrop should propagate the error",
            )
        }

    // ── Clean close fires onTransportDrop(null) before onDisconnect(null) ───

    @Test
    fun `clean close fires onTransportDrop null before onDisconnect null`() =
        runTest(StandardTestDispatcher(testScheduler)) {
            val order = CopyOnWriteArrayList<String>()
            val disconnectCalled = CompletableDeferred<Unit>()

            val transport = MockTransport()
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
                            disconnectCalled.complete(Unit)
                        }
                    },
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            testClient = client

            client.close()
            client.done.await()

            disconnectCalled.await()
            assertEquals(listOf("onTransportDrop", "onDisconnect"), order)
        }

    // ── onTransportDrop callback safety ────────────────────────────────────

    @Test
    fun `throwing onTransportDrop does not prevent onDisconnect from firing`() =
        runTest(StandardTestDispatcher(testScheduler)) {
            val disconnectCalled = CompletableDeferred<Unit>()

            val transport = MockTransport()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onTransportDrop = { throw RuntimeException("callback boom") }
                        onDisconnect = { disconnectCalled.complete(Unit) }
                    },
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            testClient = client

            transport.injectClose()

            disconnectCalled.await()
        }

    @Test
    fun `throwing onTransportDrop in reconnect loop does not abort reconnect`() =
        runTest(StandardTestDispatcher(testScheduler)) {
            val restoreCount = AtomicInteger(0)
            val secondRestoreCalled = CompletableDeferred<Unit>()

            val transport1 = MockTransport()
            val transport2 = MockTransport()
            val transport3 = MockTransport()
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
                        onTransportRestore = {
                            if (restoreCount.incrementAndGet() >= 2) {
                                secondRestoreCalled.complete(Unit)
                            }
                        }
                        autoReconnect =
                            AutoReconnectConfig(
                                maxRetries = 5,
                                baseDelay = 1.milliseconds,
                                maxDelay = 5.milliseconds,
                            )
                    },
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            testClient = client

            // Drop first transport — triggers handleTransportDrop.
            transport1.injectClose()

            // Reconnect loop should recover and connect with transport2.
            testScheduler.advanceTimeBy(20)
            testScheduler.runCurrent()

            // Drop second transport — triggers onTransportDrop from inside reconnectLoop.
            transport2.injectClose()

            // Reconnect loop should continue (not abort) despite throwing onTransportDrop.
            testScheduler.advanceTimeBy(20)
            testScheduler.runCurrent()

            secondRestoreCalled.await()
            assertEquals(2, restoreCount.get())
        }
}
