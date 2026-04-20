package com.wspulse.client.component

import com.wspulse.client.Message
import com.wspulse.client.TransportFrame
import com.wspulse.client.WspulseClient
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
            val received = CopyOnWriteArrayList<Message>()

            val transport = MockTransport()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig { onMessage = { msg -> received.add(msg) } },
                    dialer,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            testClient = client

            val senders = 50
            val msgsPerSender = 5
            val total = senders * msgsPerSender

            // Launch concurrent senders.
            val jobs =
                (0 until senders).map { s ->
                    async {
                        for (m in 0 until msgsPerSender) {
                            client.send(
                                Message(
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
}
