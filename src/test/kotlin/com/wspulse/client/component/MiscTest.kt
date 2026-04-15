package com.wspulse.client.component

import com.wspulse.client.ConnectionLostException
import com.wspulse.client.Frame
import com.wspulse.client.TransportFrame
import com.wspulse.client.WspulseClient
import com.wspulse.client.WspulseException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Miscellaneous component tests for [WspulseClient].
 *
 * Uses [MockTransport] and [MockDialer] to eliminate network I/O.
 */
class MiscTest : ComponentTestBase(TestCoroutineScheduler()) {
    // ── Scenario 8: concurrent sends ────────────────────────────────────────

    @Test
    fun `concurrent sends from multiple coroutines do not race`() =
        runTest(StandardTestDispatcher(testScheduler)) {
            val received = CopyOnWriteArrayList<Frame>()

            val transport = MockTransport()
            val pongResponder = transport.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig { onMessage = { frame -> received.add(frame) } },
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
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
                                Frame(
                                    event = "concurrent",
                                    payload = mapOf("s" to s, "m" to m),
                                ),
                            )
                        }
                    }
                }
            jobs.awaitAll()

            // Wait for all writes to appear in the transport.
            waitUntil {
                transport.sent.count { it is TransportFrame.Text } >= total
            }

            // Inject echoes.
            val textFrames = transport.sent.filterIsInstance<TransportFrame.Text>()
            for (f in textFrames) {
                transport.injectText(f.data)
            }

            waitUntil { received.size >= total }

            assertEquals(total, received.size)
            assertTrue(received.all { it.event == "concurrent" })
        }

    // ── Scenario 7: pong timeout ────────────────────────────────────────────

    @Test
    fun `pong timeout triggers ConnectionLostException`() =
        runTest(StandardTestDispatcher(testScheduler)) {
            val disconnectErr = AtomicReference<WspulseException?>(null)
            val disconnectCalled = CompletableDeferred<Unit>()

            val transport = MockTransport()
            // Do NOT auto-pong -- let the pong deadline fire.
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onDisconnect = { err ->
                            disconnectErr.set(err)
                            disconnectCalled.complete(Unit)
                        }
                        pingInterval = 100.milliseconds
                        writeTimeout = 300.milliseconds
                    },
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            testClient = client

            // Advance past pong deadline (300 ms) then assert immediately.
            testScheduler.advanceTimeBy(350)
            testScheduler.runCurrent()

            assertTrue(disconnectCalled.isCompleted)

            assertTrue(
                disconnectErr.get() is ConnectionLostException,
                "expected ConnectionLostException but got: ${disconnectErr.get()}",
            )
        }
}
