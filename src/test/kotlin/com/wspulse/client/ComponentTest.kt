package com.wspulse.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Deterministic component tests for [WspulseClient].
 *
 * Uses [MockTransport] and [MockDialer] to eliminate network I/O.
 * Each test exercises the client's internal coroutine machinery
 * (readLoop, writeLoop, pingLoop, reconnectLoop) through controlled
 * frame injection and transport lifecycle events.
 *
 * Runs as part of `./gradlew test` -- no special tags or external
 * dependencies required.
 */
class ComponentTest {
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

    // ── Scenario 3: auto-reconnect after transport drop ─────────────────────

    @Test
    fun `reconnects after transport drop and resumes message flow`() =
        kotlinx.coroutines.test.runTest {
            val received = CopyOnWriteArrayList<Frame>()
            val transportRestored = CountDownLatch(1)

            val transport1 = MockTransport()
            val transport2 = MockTransport()
            val pongResponder1 = transport1.autoPong()
            val pongResponder2 = transport2.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport1), Result.success(transport2)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onMessage = { frame -> received.add(frame) }
                        onTransportRestore = { transportRestored.countDown() }
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

            // Respond to initial ping on transport1.
            waitForPing(transport1)
            pongResponder1.tick()

            // Send a frame before drop.
            client.send(Frame(event = "before", payload = "drop"))
            waitUntil { transport1.sent.any { it is io.ktor.websocket.Frame.Text } }
            transport1.injectText("""{"event":"before","payload":"drop"}""")
            waitUntil { received.any { it.event == "before" } }

            // Drop transport1.
            transport1.injectClose()

            // Auto-pong on transport2 after reconnect.
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    while (dialer.dialCount < 2) {
                        pongResponder1.tick()
                        delay(10)
                    }
                }
            }

            // Wait for reconnect and respond to pings on transport2.
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    while (!transportRestored.await(10, TimeUnit.MILLISECONDS)) {
                        pongResponder2.tick()
                    }
                }
            }

            // Send after reconnect.
            client.send(Frame(event = "after", payload = "reconnect"))
            waitUntil { transport2.sent.any { it is io.ktor.websocket.Frame.Text } }
            transport2.injectText("""{"event":"after","payload":"reconnect"}""")
            waitUntil { received.any { it.event == "after" } }
        }

    // ── Scenario 4: max retries exhausted ───────────────────────────────────

    @Test
    fun `fires RetriesExhaustedException after max retries exhausted`() =
        kotlinx.coroutines.test.runTest {
            val disconnectErr = AtomicReference<WspulseException?>(null)
            val disconnectCalled = CountDownLatch(1)

            val transport = MockTransport()
            val pongResponder = transport.autoPong()

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
                            disconnectCalled.countDown()
                        }
                        autoReconnect =
                            AutoReconnectConfig(
                                maxRetries = 2,
                                baseDelay = 1.milliseconds,
                                maxDelay = 10.milliseconds,
                            )
                    },
                    dialer,
                )
            testClient = client

            // Respond to initial ping.
            waitForPing(transport)
            pongResponder.tick()

            // Drop the transport to start reconnect loop.
            transport.injectClose()

            // Wait for retries to exhaust.
            assertTrue(disconnectCalled.await(10, TimeUnit.SECONDS))

            assertTrue(
                disconnectErr.get() is RetriesExhaustedException,
                "expected RetriesExhaustedException but got: ${disconnectErr.get()}",
            )
        }

    // ── Scenario 5: close during reconnect ──────────────────────────────────

    @Test
    fun `close during reconnect fires onDisconnect null`() =
        kotlinx.coroutines.test.runTest {
            val disconnectErr = AtomicReference<WspulseException?>(null)
            val disconnectCalled = CountDownLatch(1)
            val transportDropSignal = CompletableDeferred<Unit>()

            val transport = MockTransport()
            val pongResponder = transport.autoPong()

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
                            disconnectCalled.countDown()
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
                )
            testClient = client

            // Respond to initial ping.
            waitForPing(transport)
            pongResponder.tick()

            // Drop to trigger reconnect.
            transport.injectClose()

            // Wait for transport drop callback.
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { transportDropSignal.await() }
            }

            // Close during reconnect.
            client.close()

            assertTrue(disconnectCalled.await(10, TimeUnit.SECONDS))
            assertNull(disconnectErr.get())
        }

    // ── Scenario 6: send after close ────────────────────────────────────────

    @Test
    fun `send after close throws ConnectionClosedException`() =
        kotlinx.coroutines.test.runTest {
            val transport = MockTransport()
            val pongResponder = transport.autoPong()
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client = WspulseClient.connectInternal("ws://test", clientConfig {}, dialer)
            testClient = client

            waitForPing(transport)
            pongResponder.tick()

            client.close()
            client.done.await()

            assertThrows<ConnectionClosedException> {
                client.send(Frame(event = "msg"))
            }
        }

    // ── Scenario 7: pong timeout ────────────────────────────────────────────

    @Test
    fun `pong timeout triggers ConnectionLostException`() =
        kotlinx.coroutines.test.runTest {
            val disconnectErr = AtomicReference<WspulseException?>(null)
            val disconnectCalled = CountDownLatch(1)

            val transport = MockTransport()
            // Do NOT auto-pong -- let the pong deadline fire.
            val dialer = MockDialer(listOf(Result.success(transport)))

            val client =
                WspulseClient.connectInternal(
                    "ws://test",
                    clientConfig {
                        onDisconnect = { err ->
                            disconnectErr.set(err)
                            disconnectCalled.countDown()
                        }
                        heartbeat =
                            HeartbeatConfig(
                                pingPeriod = 100.milliseconds,
                                pongWait = 300.milliseconds,
                            )
                    },
                    dialer,
                )
            testClient = client

            // Wait for pong timeout -> transport close -> disconnect.
            assertTrue(disconnectCalled.await(10, TimeUnit.SECONDS))

            assertTrue(
                disconnectErr.get() is ConnectionLostException,
                "expected ConnectionLostException but got: ${disconnectErr.get()}",
            )
        }

    // ── Scenario 8: concurrent sends ────────────────────────────────────────

    @Test
    fun `concurrent sends from multiple coroutines do not race`() =
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

            val senders = 50
            val msgsPerSender = 5
            val total = senders * msgsPerSender

            // Launch concurrent senders.
            val jobs =
                (0 until senders).map { s ->
                    async {
                        for (m in 0 until msgsPerSender) {
                            client.send(
                                Frame(event = "concurrent", payload = mapOf("s" to s, "m" to m)),
                            )
                        }
                    }
                }
            jobs.awaitAll()

            // Wait for all writes to appear in the transport.
            waitUntil(timeoutMs = 10_000) {
                transport.sent.count { it is io.ktor.websocket.Frame.Text } >= total
            }

            // Inject echoes.
            val textFrames = transport.sent.filterIsInstance<io.ktor.websocket.Frame.Text>()
            for (f in textFrames) {
                transport.injectText(String(f.data, Charsets.UTF_8))
            }

            waitUntil(timeoutMs = 10_000) { received.size >= total }

            assertEquals(total, received.size)
            assertTrue(received.all { it.event == "concurrent" })
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
            withContext(Dispatchers.Default) { delay(200) }

            assertEquals(1, disconnectCount.get())
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
                )
            testClient = client

            waitForPing(transport)
            pongResponder.tick()

            // Call close multiple times concurrently.
            val jobs =
                (0 until 5).map {
                    launch { client.close() }
                }
            jobs.forEach { it.join() }
            client.done.await()

            assertEquals(1, disconnectCount.get())
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

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Create a [ClientConfig] with short heartbeat defaults for testing.
     */
    private fun clientConfig(init: ClientConfig.() -> Unit = {}): ClientConfig =
        ClientConfig().apply {
            // Long heartbeat so ping/pong doesn't interfere with most tests.
            heartbeat = HeartbeatConfig(pingPeriod = 50.seconds, pongWait = 60.seconds)
            init()
        }

    /**
     * Spin-wait with timeout for a condition to become true.
     * Uses real wall-clock time (Dispatchers.Default) to avoid
     * interference with runTest's virtual time scheduler.
     */
    private suspend fun waitUntil(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ) {
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMs.milliseconds) {
                while (!condition()) {
                    delay(10)
                }
            }
        }
    }

    /**
     * Wait until the transport has received at least one Ping frame.
     */
    private suspend fun waitForPing(transport: MockTransport) {
        waitUntil {
            transport.sent.any { it is io.ktor.websocket.Frame.Ping }
        }
    }
}
