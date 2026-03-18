package com.wspulse.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests — client-kt against a live wspulse/server.
 *
 * The shared Go testserver is spawned via [ProcessBuilder] in [beforeAll] and
 * killed in [afterAll]. It echoes all inbound frames back to the sender and
 * exposes a control HTTP API for test orchestration.
 *
 * Tagged "integration" — excluded from `./gradlew test` by default.
 * Run with: `./gradlew integrationTest`
 */
@Tag("integration")
class ClientIntegrationTest {
    companion object {
        private var serverProcess: Process? = null
        private var serverUrl: String = ""
        private var controlUrl: String = ""

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            val testserverDir = findTestserverDir()

            // Build the testserver binary first.
            val binaryName = if (System.getProperty("os.name").lowercase().contains("win")) "testserver.exe" else "testserver"
            val build =
                ProcessBuilder("go", "build", "-o", binaryName, ".")
                    .directory(testserverDir)
                    .redirectErrorStream(true)
                    .start()
            val buildExitCode = build.waitFor()
            if (buildExitCode != 0) {
                val output = build.inputStream.bufferedReader().readText()
                throw IllegalStateException("testserver build failed (exit=$buildExitCode): $output")
            }

            // Start the testserver.
            val executable = java.io.File(testserverDir, binaryName).absolutePath
            val proc =
                ProcessBuilder(executable)
                    .directory(testserverDir)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start()
            serverProcess = proc

            // Read "READY:<ws_port>:<control_port>" from stderr (max 30 s).
            val stderrReader = BufferedReader(InputStreamReader(proc.errorStream))
            val readyLine = CompletableDeferred<String>()

            val readerThread =
                Thread {
                    try {
                        var line: String?
                        while (stderrReader.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            if (l.startsWith("READY:")) {
                                readyLine.complete(l)
                            }
                        }
                    } catch (_: Exception) {
                        // Process killed — expected during teardown.
                    }
                }.apply {
                    isDaemon = true
                    start()
                }

            val (wsPort, controlPort) =
                runBlocking {
                    withTimeout(30.seconds) {
                        val line = readyLine.await()
                        val parts = line.removePrefix("READY:").trim().split(":")
                        if (parts.size != 2) {
                            throw IllegalStateException(
                                "invalid READY line from testserver (expected 'READY:<ws_port>:<control_port>'): '$line'",
                            )
                        }
                        val ws =
                            parts[0].toIntOrNull()
                                ?: throw IllegalStateException("invalid WebSocket port in READY line: '$line'")
                        val ctl =
                            parts[1].toIntOrNull()
                                ?: throw IllegalStateException("invalid control port in READY line: '$line'")
                        ws to ctl
                    }
                }

            serverUrl = "ws://127.0.0.1:$wsPort"
            controlUrl = "http://127.0.0.1:$controlPort"
        }

        @JvmStatic
        @AfterAll
        fun afterAll() {
            serverProcess?.let { proc ->
                proc.destroy()
                if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            }
            serverProcess = null
        }

        private fun findTestserverDir(): java.io.File {
            // Walk up from the working directory to find testserver/.
            var dir = java.io.File(System.getProperty("user.dir"))
            while (dir.parentFile != null) {
                val candidate = java.io.File(dir, "testserver")
                if (candidate.isDirectory && java.io.File(candidate, "main.go").exists()) {
                    return candidate
                }
                dir = dir.parentFile
            }
            throw IllegalStateException("testserver directory not found")
        }
    }

    private var testClient: Client? = null

    @AfterEach
    fun tearDown() {
        runBlocking {
            testClient?.let {
                it.close()
                it.done.await()
            }
            testClient = null
        }
    }

    // ── tests ───────────────────────────────────────────────────────────────

    @Test
    fun `connects, sends a frame, receives echo, and closes cleanly`() =
        runTest {
            val received = CopyOnWriteArrayList<Frame>()
            val disconnectErr = AtomicReference<WspulseException?>(null)
            val disconnectCalled = CountDownLatch(1)

            val client =
                WspulseClient.connect(serverUrl) {
                    onMessage = { frame -> received.add(frame) }
                    onDisconnect = { err ->
                        disconnectErr.set(err)
                        disconnectCalled.countDown()
                    }
                }
            testClient = client

            client.send(Frame(event = "msg", payload = mapOf("text" to "hello")))

            // Wait for echo.
            waitUntil { received.size >= 1 }

            assertEquals("msg", received[0].event)
            assertEquals(mapOf("text" to "hello"), received[0].payload)

            client.close()
            client.done.await()

            assertTrue(disconnectCalled.await(5, TimeUnit.SECONDS))
            assertNull(disconnectErr.get())
        }

    @Test
    fun `round-trips all Frame fields (id, event, payload)`() =
        runTest {
            val received = CopyOnWriteArrayList<Frame>()

            val client =
                WspulseClient.connect(serverUrl) {
                    onMessage = { frame -> received.add(frame) }
                }
            testClient = client

            val outbound =
                Frame(
                    id = "test-id-001",
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

            waitUntil { received.size >= 1 }

            assertEquals(outbound, received[0])
        }

    @Test
    fun `handles server rejection gracefully`() =
        runTest {
            val rejectUrl = "$serverUrl?reject=1"

            val e =
                assertThrows<Exception> {
                    runBlocking {
                        val client = WspulseClient.connect(rejectUrl)
                        // If connect somehow succeeds (shouldn't), clean up.
                        testClient = client
                    }
                }
            assertTrue(e.message?.isNotBlank() == true, "exception should have a message")
        }

    @Test
    fun `sends multiple frames and receives them in order`() =
        runTest {
            val received = CopyOnWriteArrayList<Frame>()

            val client =
                WspulseClient.connect(serverUrl) {
                    onMessage = { frame -> received.add(frame) }
                }
            testClient = client

            val count = 10
            for (i in 0 until count) {
                client.send(Frame(event = "seq", payload = mapOf("i" to i)))
            }

            waitUntil { received.size >= count }

            for (i in 0 until count) {
                assertEquals("seq", received[i].event)
                assertEquals(mapOf("i" to i), received[i].payload)
            }
        }

    @Test
    fun `send after close throws ConnectionClosedException`() =
        runTest {
            val client = WspulseClient.connect(serverUrl)
            testClient = client

            client.close()
            client.done.await()

            assertThrows<ConnectionClosedException> {
                client.send(Frame(event = "msg"))
            }
        }

    @Test
    fun `connects to a specific room via query param`() =
        runTest {
            val received = CopyOnWriteArrayList<Frame>()

            val client =
                WspulseClient.connect("$serverUrl?room=myroom") {
                    onMessage = { frame -> received.add(frame) }
                }
            testClient = client

            client.send(Frame(event = "ping", payload = "pong"))

            waitUntil { received.size >= 1 }

            assertEquals("ping", received[0].event)
            assertEquals("pong", received[0].payload)
        }

    @Test
    fun `concurrent sends do not race`() =
        runTest {
            val received = CopyOnWriteArrayList<Frame>()

            val client =
                WspulseClient.connect(serverUrl) {
                    onMessage = { frame -> received.add(frame) }
                }
            testClient = client

            val senders = 50
            val msgsPerSender = 5
            val total = senders * msgsPerSender

            // Launch concurrent senders.
            val jobs =
                (0 until senders).map { s ->
                    async {
                        for (m in 0 until msgsPerSender) {
                            client.send(Frame(event = "concurrent", payload = mapOf("s" to s, "m" to m)))
                        }
                    }
                }
            jobs.awaitAll()

            // Wait for all echoes.
            waitUntil(timeoutMs = 10_000) { received.size >= total }

            assertEquals(total, received.size)
            assertTrue(received.all { it.event == "concurrent" })
        }

    @Test
    fun `onDisconnect fires exactly once on close`() =
        runTest {
            val disconnectCount = AtomicInteger(0)

            val client =
                WspulseClient.connect(serverUrl) {
                    onDisconnect = { disconnectCount.incrementAndGet() }
                }
            testClient = client

            client.close()
            client.done.await()

            // Give a brief window for any erroneous second call.
            delay(200)

            assertEquals(1, disconnectCount.get())
        }

    @Test
    fun `close is idempotent`() =
        runTest {
            val disconnectCount = AtomicInteger(0)

            val client =
                WspulseClient.connect(serverUrl) {
                    onDisconnect = { disconnectCount.incrementAndGet() }
                }
            testClient = client

            // Call close multiple times concurrently.
            val jobs =
                (0 until 5).map {
                    launch { client.close() }
                }
            jobs.forEach { it.join() }
            client.done.await()

            assertEquals(1, disconnectCount.get())
        }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Spin-wait with timeout for a condition to become true.
     */
    private suspend fun waitUntil(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ) {
        // Use Dispatchers.Default so withTimeout and delay use real wall clock
        // time instead of runTest's virtual time scheduler.
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMs.milliseconds) {
                while (!condition()) {
                    delay(50)
                }
            }
        }
    }
}
