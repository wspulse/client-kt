package com.wspulse.client

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TransportFrameTest {
    // ── NO_STATUS_RECEIVED placement ─────────────────────────────────────────
    // RFC 6455 §7.4.2: 1005 MUST NOT be sent in a close frame.
    // The constant must live on the receive-side type (TransportFrame.Close),
    // not on the outbound type (TransportCloseReason).

    @Test
    fun `NO_STATUS_RECEIVED is defined on TransportFrame Close`() {
        assertEquals(1005.toShort(), TransportFrame.Close.NO_STATUS_RECEIVED)
    }

    @Test
    fun `NO_STATUS_RECEIVED is not defined on TransportCloseReason`() {
        // Compile-time guard: TransportCloseReason has no NO_STATUS_RECEIVED field.
        // If this class compiles and the companion on TransportCloseReason still exists,
        // the test below will fail — keeping the accidental constant removed.
        val fields = TransportCloseReason.Companion::class.java.declaredFields.map { it.name }
        assert("NO_STATUS_RECEIVED" !in fields) {
            "TransportCloseReason.NO_STATUS_RECEIVED must be removed — it is an outbound type " +
                "and RFC 6455 §7.4.2 forbids sending code 1005"
        }
    }
}
