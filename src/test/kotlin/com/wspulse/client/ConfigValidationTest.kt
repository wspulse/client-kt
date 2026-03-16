package com.wspulse.client

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for config validation at construction time.
 *
 * All assertions expect [IllegalArgumentException] for invalid values.
 * Matches client-go's fail-fast validation in option functions.
 */
class ConfigValidationTest {

    // ── maxMessageSize ──────────────────────────────────────────────────────

    @Test
    fun `negative maxMessageSize throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    maxMessageSize = -1
                }
            }
        }
    }

    @Test
    fun `maxMessageSize exceeding 64 MiB throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    maxMessageSize = (64L shl 20) + 1
                }
            }
        }
    }

    // ── writeWait ───────────────────────────────────────────────────────────

    @Test
    fun `zero writeWait throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    writeWait = 0.seconds
                }
            }
        }
    }

    @Test
    fun `writeWait exceeding 30s throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    writeWait = 31.seconds
                }
            }
        }
    }

    // ── heartbeat ───────────────────────────────────────────────────────────

    @Test
    fun `zero pingPeriod throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    heartbeat = HeartbeatConfig(pingPeriod = 0.seconds)
                }
            }
        }
    }

    @Test
    fun `pingPeriod exceeding 1m throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    heartbeat = HeartbeatConfig(pingPeriod = 61.seconds)
                }
            }
        }
    }

    @Test
    fun `zero pongWait throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    heartbeat = HeartbeatConfig(pongWait = 0.seconds)
                }
            }
        }
    }

    @Test
    fun `pongWait exceeding 2m throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    heartbeat = HeartbeatConfig(pongWait = 2.minutes + 1.seconds)
                }
            }
        }
    }

    // ── autoReconnect ───────────────────────────────────────────────────────

    @Test
    fun `zero baseDelay throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    autoReconnect = AutoReconnectConfig(baseDelay = 0.seconds)
                }
            }
        }
    }

    @Test
    fun `baseDelay exceeding 1m throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    autoReconnect = AutoReconnectConfig(baseDelay = 61.seconds)
                }
            }
        }
    }

    @Test
    fun `maxDelay less than baseDelay throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    autoReconnect = AutoReconnectConfig(
                        baseDelay = 5.seconds,
                        maxDelay = 3.seconds,
                    )
                }
            }
        }
    }

    @Test
    fun `maxDelay exceeding 5m throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    autoReconnect = AutoReconnectConfig(maxDelay = 5.minutes + 1.seconds)
                }
            }
        }
    }

    @Test
    fun `maxRetries exceeding 32 throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    autoReconnect = AutoReconnectConfig(maxRetries = 33)
                }
            }
        }
    }

    // ── valid edge cases (should NOT throw) ─────────────────────────────────

    @Test
    fun `maxMessageSize zero is valid (use default)`() {
        // 0 means "no limit" — should not throw validation error.
        // Connection will fail (port 1 unreachable) but that's not a validation error.
        val ex = assertThrows<Exception> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    maxMessageSize = 0
                }
            }
        }
        // Should fail with connection error, NOT IllegalArgumentException.
        assert(ex !is IllegalArgumentException) {
            "maxMessageSize=0 should be valid"
        }
    }

    @Test
    fun `maxRetries zero means unlimited and is valid`() = runBlocking {
        // maxRetries=0 means unlimited — validation should not reject.
        // With autoReconnect config, connect enters RECONNECTING (no throw).
        val client = WspulseClient.connect("ws://127.0.0.1:1") {
            autoReconnect = AutoReconnectConfig(maxRetries = 0)
        }
        client.close()
        client.done.await()
    }
}
