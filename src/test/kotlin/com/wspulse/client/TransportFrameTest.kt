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

    // TransportCloseReason must not contain NO_STATUS_RECEIVED.
    // Absence is enforced at compile time: any reference to
    // TransportCloseReason.NO_STATUS_RECEIVED will fail to compile.
}
