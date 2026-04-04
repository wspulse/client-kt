package com.wspulse.client.component

import com.wspulse.client.Client
import com.wspulse.client.ClientConfig
import com.wspulse.client.ConnectionClosedException
import com.wspulse.client.Frame
import com.wspulse.client.HeartbeatConfig
import com.wspulse.client.TransportFrame
import com.wspulse.client.WspulseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Client lifecycle component tests for [WspulseClient].
 *
 * Uses [MockTransport] and [MockDialer] to eliminate network I/O.
 */
class LifecycleTest {
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

    // ── Scenario 6: send after close ────────────────────────────────────────

    @Test
    fun `send after close throws ConnectionClosedException`() =
        kotlinx.coroutines.test.runTest {
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

            // Call close multiple times concurrently.
            val jobs = (0 until 5).map { launch { client.close() } }
            jobs.forEach { it.join() }
            client.done.await()

            assertEquals(1, disconnectCount.get())
        }

    // ── Scenario 9: close racing with transport drop ────────────────────────

    @Test
    fun `close racing with transport drop fires onDisconnect exactly once`() =
        kotlinx.coroutines.test.runTest {
            val disconnectCount = AtomicInteger(0)
            val disconnectCalled = CountDownLatch(1)

            val transport = MockTransport()
            val pongResponder = transport.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onDisconnect = {
                            disconnectCount.incrementAndGet()
                            disconnectCalled.countDown()
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

            assertTrue(disconnectCalled.await(5, TimeUnit.SECONDS))

            // Brief window for any erroneous second call.
            testScheduler.advanceTimeBy(200)

            assertEquals(1, disconnectCount.get())
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
        waitUntil {
            transport.sent.any { it is TransportFrame.Ping }
        }
    }
}
