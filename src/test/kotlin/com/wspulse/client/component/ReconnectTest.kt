package com.wspulse.client.component

import com.wspulse.client.AutoReconnectConfig
import com.wspulse.client.Dialer
import com.wspulse.client.Message
import com.wspulse.client.RetriesExhaustedException
import com.wspulse.client.Transport
import com.wspulse.client.TransportFrame
import com.wspulse.client.WspulseClient
import com.wspulse.client.WspulseException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Reconnect flow component tests for [WspulseClient].
 *
 * Uses [MockTransport] and [MockDialer] to eliminate network I/O.
 */
class ReconnectTest : ComponentTestBase(TestCoroutineScheduler()) {
    // ── Scenario 3: auto-reconnect after transport drop ─────────────────────

    @Test
    fun `reconnects after transport drop and resumes message flow`() =
        runTest(StandardTestDispatcher(testScheduler)) {
            val received = CopyOnWriteArrayList<Message>()
            val transportRestored = CompletableDeferred<Unit>()

            val transport1 = MockTransport()
            val transport2 = MockTransport()
            val dialer =
                MockDialer(listOf(Result.success(transport1), Result.success(transport2)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onMessage = { msg -> received.add(msg) }
                        onTransportRestore = { transportRestored.complete(Unit) }
                        autoReconnect =
                            AutoReconnectConfig(
                                maxRetries = 5,
                                baseDelay = 1.milliseconds,
                                maxDelay = 10.milliseconds,
                            )
                    },
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    random = Random(42),
                )
            testClient = client

            // Send a message before drop.
            client.send(Message(event = "before", payload = "drop"))
            waitUntil { transport1.sent.any { it is TransportFrame.Text } }
            transport1.injectText("""{"event":"before","payload":"drop"}""")
            waitUntil { received.any { it.event == "before" } }

            // Drop transport1.
            transport1.injectClose()

            // Advance past reconnect backoff (1 ms base, 10 ms max).
            testScheduler.advanceTimeBy(20)
            testScheduler.runCurrent()

            assertTrue(transportRestored.isCompleted)

            // Send after reconnect.
            client.send(Message(event = "after", payload = "reconnect"))
            waitUntil { transport2.sent.any { it is TransportFrame.Text } }
            transport2.injectText("""{"event":"after","payload":"reconnect"}""")
            waitUntil { received.any { it.event == "after" } }
        }

    // ── Scenario 4: max retries exhausted ───────────────────────────────────

    @Test
    fun `fires RetriesExhaustedException after max retries exhausted`() =
        runTest(StandardTestDispatcher(testScheduler)) {
            val disconnectErr = AtomicReference<WspulseException?>(null)
            val disconnectCalled = CompletableDeferred<Unit>()

            val transport = MockTransport()
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
                            disconnectCalled.complete(Unit)
                        }
                        autoReconnect =
                            AutoReconnectConfig(
                                maxRetries = 2,
                                baseDelay = 1.milliseconds,
                                maxDelay = 10.milliseconds,
                            )
                    },
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    random = Random(42),
                )
            testClient = client

            // Drop the transport to start reconnect loop.
            transport.injectClose()

            // Advance past all backoff delays for 2 retry attempts.
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()

            assertTrue(disconnectCalled.isCompleted)

            assertTrue(
                disconnectErr.get() is RetriesExhaustedException,
                "expected RetriesExhaustedException but got: ${disconnectErr.get()}",
            )
        }

    // ── Scenario 5: close during reconnect ──────────────────────────────────

    @Test
    fun `close during reconnect fires onDisconnect null`() =
        runTest(StandardTestDispatcher(testScheduler)) {
            val disconnectErr = AtomicReference<WspulseException?>(null)
            val disconnectCalled = CompletableDeferred<Unit>()
            val transportDropSignal = CompletableDeferred<Unit>()

            val transport = MockTransport()
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
                            disconnectCalled.complete(Unit)
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
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    random = Random(42),
                )
            testClient = client

            // Drop to trigger reconnect.
            transport.injectClose()

            // Wait for transport drop callback (fires eagerly with UnconfinedTestDispatcher).
            withTimeout(1.seconds) { transportDropSignal.await() }

            // Advance past initial backoff so the slow dial starts.
            testScheduler.advanceTimeBy(5)

            // Close during reconnect.
            client.close()

            assertTrue(disconnectCalled.isCompleted)
            assertNull(disconnectErr.get())
        }
}
