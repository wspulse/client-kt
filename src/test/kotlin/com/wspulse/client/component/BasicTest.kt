package com.wspulse.client.component

import com.wspulse.client.Message
import com.wspulse.client.TransportFrame
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
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Basic connect/send/receive component tests for [WspulseClient].
 *
 * Uses [MockTransport] and [MockDialer] to eliminate network I/O.
 */
class BasicTest : ComponentTestBase(TestCoroutineScheduler()) {
    // ── Scenario 1: connect, send, receive echo, close ──────────────────────

    @Test
    fun `connects, sends a message, receives echo, and closes cleanly`() =
        runTest(StandardTestDispatcher(testScheduler)) {
            val received = CopyOnWriteArrayList<Message>()
            val disconnectErr = AtomicReference<WspulseException?>(null)
            val disconnectCalled = CompletableDeferred<Unit>()
            val transportDropFired = CompletableDeferred<Unit>()
            val transportDropWasNull =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)

            val transport = MockTransport()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onMessage = { msg -> received.add(msg) }
                        onTransportDrop = { err ->
                            transportDropWasNull.set(err == null)
                            transportDropFired.complete(Unit)
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

            // Client sends a message.
            client.send(Message(event = "msg", payload = mapOf("text" to "hello")))

            // Wait for writeLoop to pick up the message.
            waitUntil { transport.sent.any { it is TransportFrame.Text } }

            // Simulate server echo.
            transport.injectText("""{"event":"msg","payload":{"text":"hello"}}""")

            waitUntil { received.size >= 1 }

            assertEquals("msg", received[0].event)
            assertEquals(mapOf("text" to "hello"), received[0].payload)

            client.close()
            client.done.await()

            transportDropFired.await()
            disconnectCalled.await()
            assertTrue(
                transportDropWasNull.get(),
                "onTransportDrop should receive null on clean close",
            )
            assertNull(disconnectErr.get())
        }

    // ── Message round-trip ──────────────────────────────────────────────────

    @Test
    fun `round-trips all Message fields (event, payload)`() =
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

            val outbound =
                Message(
                    event = "chat.message",
                    payload =
                        mapOf(
                            "user" to "alice",
                            "text" to "hi",
                            "n" to 42,
                            "nested" to mapOf("ok" to true),
                        ),
                )
            client.send(outbound)

            // Wait for the write to appear.
            waitUntil { transport.sent.any { it is TransportFrame.Text } }

            // Echo the exact wire bytes back.
            val textFrame =
                transport.sent.first { it is TransportFrame.Text } as TransportFrame.Text
            transport.injectText(textFrame.data)

            waitUntil { received.size >= 1 }

            assertEquals(outbound, received[0])
        }

    // ── Server rejection (dial failure) ─────────────────────────────────────

    @Test
    fun `handles server rejection gracefully`() =
        runTest(StandardTestDispatcher(testScheduler)) {
            val dialer =
                MockDialer(
                    listOf(Result.failure(Exception("wspulse: connection rejected"))),
                )

            try {
                val client =
                    WspulseClient.connectInternal(
                        "ws://test",
                        clientConfig {},
                        dialer,
                        dispatcher = UnconfinedTestDispatcher(testScheduler),
                    )
                testClient = client
                fail("Expected exception from connectInternal")
            } catch (e: Exception) {
                assertTrue(e.message?.isNotBlank() == true, "exception should have a message")
            }
        }

    // ── Message ordering ────────────────────────────────────────────────────

    @Test
    fun `sends multiple messages and receives them in order`() =
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

            val count = 10
            for (i in 0 until count) {
                client.send(Message(event = "seq", payload = mapOf("i" to i)))
            }

            // Wait for all writes.
            waitUntil { transport.sent.count { it is TransportFrame.Text } >= count }

            // Echo each in order.
            val textFrames = transport.sent.filterIsInstance<TransportFrame.Text>()
            for (f in textFrames) {
                transport.injectText(f.data)
            }

            waitUntil { received.size >= count }

            for (i in 0 until count) {
                assertEquals("seq", received[i].event)
                assertEquals(mapOf("i" to i), received[i].payload)
            }
        }
}
