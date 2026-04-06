package com.wspulse.client.component

import com.wspulse.client.ConnectionClosedException
import com.wspulse.client.Frame
import com.wspulse.client.WspulseClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client lifecycle component tests for [WspulseClient].
 *
 * Uses [MockTransport] and [MockDialer] to eliminate network I/O.
 */
class LifecycleTest : ComponentTestBase(TestCoroutineScheduler()) {
    // ── Scenario 6: send after close ────────────────────────────────────────

    @Test
    fun `send after close throws ConnectionClosedException`() =
        runTest(StandardTestDispatcher(testScheduler)) {
            val transport = MockTransport()
            val pongResponder = transport.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {},
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            testClient = client

            waitForPing(transport)
            pongResponder.tick()

            client.close()
            client.done.await()

            assertThrows<ConnectionClosedException> { client.send(Frame(event = "msg")) }
        }

    // ── Close idempotency ───────────────────────────────────────────────────

    @Test
    fun `close is idempotent`() =
        runTest(StandardTestDispatcher(testScheduler)) {
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

            // Call close multiple times concurrently.
            val jobs = (0 until 5).map { launch { client.close() } }
            jobs.forEach { it.join() }
            client.done.await()

            assertEquals(1, disconnectCount.get())
        }

    // ── Scenario 9: close racing with transport drop ────────────────────────

    @Test
    fun `close racing with transport drop fires onDisconnect exactly once`() =
        runTest(StandardTestDispatcher(testScheduler)) {
            val disconnectCount = AtomicInteger(0)
            val transportDropCount = AtomicInteger(0)
            val disconnectCalled = CompletableDeferred<Unit>()

            val transport = MockTransport()
            val pongResponder = transport.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onTransportDrop = { transportDropCount.incrementAndGet() }
                        onDisconnect = {
                            disconnectCount.incrementAndGet()
                            disconnectCalled.complete(Unit)
                        }
                    },
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            testClient = client

            waitForPing(transport)
            pongResponder.tick()

            // Fire close() and transport drop simultaneously.
            val dropJob = launch { transport.injectClose() }
            val closeJob = launch { client.close() }

            dropJob.join()
            closeJob.join()

            disconnectCalled.await()

            // Brief window for any erroneous second call.
            testScheduler.advanceTimeBy(200)

            assertEquals(1, transportDropCount.get())
            assertEquals(1, disconnectCount.get())
        }
}
