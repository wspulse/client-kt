package com.wspulse.client

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * Tests for URL scheme normalization in [WspulseClient.connect].
 *
 * The normalization function converts `http://` to `ws://` and
 * `https://` to `wss://`, passes `ws://` and `wss://` through
 * unchanged, and throws [IllegalArgumentException] for missing
 * or unsupported schemes.
 *
 * Tests use port 1 (unreachable) to trigger a dial failure after
 * validation passes, proving that scheme validation did not reject.
 */
class NormalizeSchemeTest {
    // ── passthrough ────────────────────────────────────────────────────────

    @Test
    fun `ws scheme passes through`() {
        val ex =
            assertThrows<Exception> {
                runBlocking {
                    WspulseClient.connect("ws://127.0.0.1:1/ws")
                }
            }
        assertFalse(
            ex is IllegalArgumentException,
            "ws:// should pass through without error",
        )
    }

    @Test
    fun `wss scheme passes through`() {
        val ex =
            assertThrows<Exception> {
                runBlocking {
                    WspulseClient.connect("wss://127.0.0.1:1/ws")
                }
            }
        assertFalse(
            ex is IllegalArgumentException,
            "wss:// should pass through without error",
        )
    }

    // ── http → ws conversion ───────────────────────────────────────────────

    @Test
    fun `http converts to ws`() {
        val ex =
            assertThrows<Exception> {
                runBlocking {
                    WspulseClient.connect("http://127.0.0.1:1/ws")
                }
            }
        assertFalse(
            ex is IllegalArgumentException,
            "http:// should be accepted and converted to ws://",
        )
    }

    @Test
    fun `https converts to wss`() {
        val ex =
            assertThrows<Exception> {
                runBlocking {
                    WspulseClient.connect("https://127.0.0.1:1/ws")
                }
            }
        assertFalse(
            ex is IllegalArgumentException,
            "https:// should be accepted and converted to wss://",
        )
    }

    @Test
    fun `http with port converts to ws`() {
        val ex =
            assertThrows<Exception> {
                runBlocking {
                    WspulseClient.connect("http://127.0.0.1:1/ws")
                }
            }
        assertFalse(
            ex is IllegalArgumentException,
            "http:// with port should be accepted and converted to ws://",
        )
    }

    @Test
    fun `https with port and query converts to wss`() {
        val ex =
            assertThrows<Exception> {
                runBlocking {
                    WspulseClient.connect("https://127.0.0.1:1/ws?token=abc")
                }
            }
        assertFalse(
            ex is IllegalArgumentException,
            "https:// with port and query should be accepted and converted to wss://",
        )
    }

    // ── mixed case schemes ───────────────────────────────────────────────

    @Test
    fun `HTTP uppercase converts to ws`() {
        val ex =
            assertThrows<Exception> {
                runBlocking {
                    WspulseClient.connect("HTTP://127.0.0.1:1/ws")
                }
            }
        assertFalse(
            ex is IllegalArgumentException,
            "HTTP:// should be accepted and converted to ws://",
        )
    }

    @Test
    fun `Https mixed case converts to wss`() {
        val ex =
            assertThrows<Exception> {
                runBlocking {
                    WspulseClient.connect("Https://127.0.0.1:1/ws")
                }
            }
        assertFalse(
            ex is IllegalArgumentException,
            "Https:// should be accepted and converted to wss://",
        )
    }

    @Test
    fun `WS uppercase passes through`() {
        val ex =
            assertThrows<Exception> {
                runBlocking {
                    WspulseClient.connect("WS://127.0.0.1:1/ws")
                }
            }
        assertFalse(
            ex is IllegalArgumentException,
            "WS:// should pass through without error",
        )
    }

    // ── error cases ────────────────────────────────────────────────────────

    @Test
    fun `missing scheme throws`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    WspulseClient.connect("/no-scheme/path")
                }
            }
        assertContains(
            ex.message ?: "",
            "wspulse: url must include scheme",
        )
    }

    @Test
    fun `unsupported scheme throws`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    WspulseClient.connect("ftp://127.0.0.1:1/ws")
                }
            }
        assertContains(
            ex.message ?: "",
            "wspulse: unsupported url scheme",
        )
    }

    @Test
    fun `malformed url throws invalid url`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    WspulseClient.connect("://no-scheme")
                }
            }
        assertContains(
            ex.message ?: "",
            "wspulse: invalid url",
        )
    }
}
