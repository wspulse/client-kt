package com.wspulse.client.component

import com.wspulse.client.Client
import com.wspulse.client.ClientConfig
import com.wspulse.client.Frame
import com.wspulse.client.HeartbeatConfig
import com.wspulse.client.WspulseClient
import com.wspulse.client.WspulseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Basic connect/send/receive component tests for [WspulseClient].
 *
 * Uses [MockTransport] and [MockDialer] to eliminate network I/O.
 */
class BasicTest {
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

    // ── Scenario 1: connect, send, receive echo, close ──────────────────────

    @Test
    fun `connects, sends a frame, receives echo, and closes cleanly`() =
        kotlinx.coroutines.test.runTest {
            val received = CopyOnWriteArrayList<Frame>()
            val disconnectErr = AtomicReference<WspulseException?>(null)
            val disconnectCalled = CountDownLatch(1)

            val transport = MockTransport()
            val pongResponder = transport.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onMessage = { frame -> received.add(frame) }
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

            // Client sends a frame.
            client.send(Frame(event = "msg", payload = mapOf("text" to "hello")))

            // Wait for writeLoop to pick up the frame.
            waitUntil { transport.sent.any { it is io.ktor.websocket.Frame.Text } }

            // Simulate server echo.
            transport.injectText("""{"event":"msg","payload":{"text":"hello"}}""")

            waitUntil { received.size >= 1 }

            assertEquals("msg", received[0].event)
            assertEquals(mapOf("text" to "hello"), received[0].payload)

            client.close()
            client.done.await()

            assertTrue(disconnectCalled.await(5, TimeUnit.SECONDS))
            assertNull(disconnectErr.get())
        }

    // ── Frame round-trip ────────────────────────────────────────────────────

    @Test
    fun `round-trips all Frame fields (event, payload)`() =
        kotlinx.coroutines.test.runTest {
            val received = CopyOnWriteArrayList<Frame>()

            val transport = MockTransport()
            val pongResponder = transport.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onMessage = { frame -> received.add(frame) }
                    },
                    dialer,
                )
            testClient = client

            waitForPing(transport)
            pongResponder.tick()

            val outbound =
                Frame(
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
            waitUntil { transport.sent.any { it is io.ktor.websocket.Frame.Text } }

            // Echo the exact wire bytes back.
            val textFrame = transport.sent.first { it is io.ktor.websocket.Frame.Text }
            transport.injectText(String(textFrame.data, Charsets.UTF_8))

            waitUntil { received.size >= 1 }

            assertEquals(outbound, received[0])
        }

    // ── Server rejection (dial failure) ─────────────────────────────────────

    @Test
    fun `handles server rejection gracefully`() =
        kotlinx.coroutines.test.runTest {
            val dialer =
                MockDialer(
                    listOf(Result.failure(Exception("wspulse: connection rejected"))),
                )

            val e =
                assertThrows<Exception> {
                    kotlinx.coroutines.runBlocking {
                        val client = WspulseClient.connectInternal("ws://test", clientConfig {}, dialer)
                        testClient = client
                    }
                }
            assertTrue(e.message?.isNotBlank() == true, "exception should have a message")
        }

    // ── Message ordering ────────────────────────────────────────────────────

    @Test
    fun `sends multiple frames and receives them in order`() =
        kotlinx.coroutines.test.runTest {
            val received = CopyOnWriteArrayList<Frame>()

            val transport = MockTransport()
            val pongResponder = transport.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onMessage = { frame -> received.add(frame) }
                    },
                    dialer,
                )
            testClient = client

            waitForPing(transport)
            pongResponder.tick()

            val count = 10
            for (i in 0 until count) {
                client.send(Frame(event = "seq", payload = mapOf("i" to i)))
            }

            // Wait for all writes.
            waitUntil {
                transport.sent.count { it is io.ktor.websocket.Frame.Text } >= count
            }

            // Echo each in order.
            val textFrames = transport.sent.filterIsInstance<io.ktor.websocket.Frame.Text>()
            for (f in textFrames) {
                transport.injectText(String(f.data, Charsets.UTF_8))
            }

            waitUntil { received.size >= count }

            for (i in 0 until count) {
                assertEquals("seq", received[i].event)
                assertEquals(mapOf("i" to i), received[i].payload)
            }
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
            transport.sent.any { it is io.ktor.websocket.Frame.Ping }
        }
    }
}
