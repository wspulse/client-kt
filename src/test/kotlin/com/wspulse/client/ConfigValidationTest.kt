package com.wspulse.client

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for config validation at construction time.
 *
 * All assertions expect [IllegalArgumentException] for invalid values.
 * Matches client-go's fail-fast validation in option functions.
 */
class ConfigValidationTest {
    // ── sendBufferSize ──────────────────────────────────────────────────────

    @Test
    fun `sendBufferSize 1 is valid`() {
        val ex =
            assertThrows<Exception> {
                runBlocking {
                    WspulseClient.connect("ws://127.0.0.1:1") {
                        sendBufferSize = 1
                    }
                }
            }
        assertFalse(ex is IllegalArgumentException, "sendBufferSize=1 should be valid")
    }

    @Test
    fun `sendBufferSize 4096 is valid`() {
        val ex =
            assertThrows<Exception> {
                runBlocking {
                    WspulseClient.connect("ws://127.0.0.1:1") {
                        sendBufferSize = 4096
                    }
                }
            }
        assertFalse(ex is IllegalArgumentException, "sendBufferSize=4096 should be valid")
    }

    @Test
    fun `sendBufferSize zero throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    sendBufferSize = 0
                }
            }
        }
    }

    @Test
    fun `negative sendBufferSize throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    sendBufferSize = -1
                }
            }
        }
    }

    @Test
    fun `sendBufferSize exceeding 4096 throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    sendBufferSize = 4097
                }
            }
        }
    }

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
                    autoReconnect =
                        AutoReconnectConfig(
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

    // ── negative values ─────────────────────────────────────────────────

    @Test
    fun `negative writeWait throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    writeWait = (-1).seconds
                }
            }
        }
    }

    @Test
    fun `negative baseDelay throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    autoReconnect = AutoReconnectConfig(baseDelay = (-1).seconds)
                }
            }
        }
    }

    @Test
    fun `negative maxRetries throws`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    autoReconnect = AutoReconnectConfig(maxRetries = -1)
                }
            }
        }
    }

    // ── valid edge cases (should NOT throw) ─────────────────────────────────

    @Test
    fun `maxMessageSize zero disables size enforcement`() {
        // 0 means "no limit" — should not throw validation error.
        // Connection will fail (port 1 unreachable) but that's not a validation error.
        val ex =
            assertThrows<Exception> {
                runBlocking {
                    WspulseClient.connect("ws://127.0.0.1:1") {
                        maxMessageSize = 0
                    }
                }
            }
        // Should fail with connection error, NOT IllegalArgumentException.
        assertFalse(ex is IllegalArgumentException, "maxMessageSize=0 should be valid")
    }

    @Test
    fun `maxRetries zero means unlimited and is valid`() {
        // maxRetries=0 means unlimited — validation should not reject.
        // Initial dial failure is always fatal, so connect throws a connection
        // error (not IllegalArgumentException), proving validation passed.
        val ex =
            assertThrows<Exception> {
                runBlocking {
                    WspulseClient.connect("ws://127.0.0.1:1") {
                        autoReconnect = AutoReconnectConfig(maxRetries = 0)
                    }
                }
            }
        assertFalse(ex is IllegalArgumentException, "maxRetries=0 should be valid")
    }

    @Test
    fun `initial dial failure throws even with autoReconnect enabled`() {
        // Initial dial is always fatal — autoReconnect only kicks in after a
        // successful initial connection subsequently drops.
        assertThrows<Exception> {
            runBlocking {
                WspulseClient.connect("ws://127.0.0.1:1") {
                    autoReconnect =
                        AutoReconnectConfig(
                            maxRetries = 5,
                        )
                }
            }
        }
    }
}
