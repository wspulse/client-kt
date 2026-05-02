package com.wspulse.client

/**
 * WebSocket close status code (RFC 6455 §7.4).
 *
 * Wraps the raw 16-bit integer close code. [Companion] exposes constants for the standard codes
 * that have defined semantics in this library. The constructor accepts any 16-bit value (0–65535)
 * so callers can represent any code received from the server; constants are not provided for
 * reserved or rarely-used entries — use `StatusCode(n)` directly for those. Application-defined
 * codes are typically in the `4000`–`4999` range.
 *
 * The inline value class keeps runtime overhead at zero while giving the API a typed,
 * self-documenting form.
 */
@JvmInline
value class StatusCode(
    val value: Int,
) {
    init {
        require(value in 0..0xFFFF) { "wspulse: StatusCode value must be in 0..65535, got $value" }
    }

    override fun toString(): String = value.toString()

    companion object {
        /** 1000 — Normal closure; the connection completed its purpose. */
        val NORMAL_CLOSURE = StatusCode(1000)

        /** 1001 — Endpoint is going away (server shutdown, browser navigation). */
        val GOING_AWAY = StatusCode(1001)

        /** 1002 — Protocol error. */
        val PROTOCOL_ERROR = StatusCode(1002)

        /** 1003 — Received data type the endpoint cannot accept. */
        val UNSUPPORTED_DATA = StatusCode(1003)

        /**
         * 1005 — No status code was present in the close frame.
         *
         * RFC 6455 §7.4.1: MUST NOT be sent on the wire; the client library synthesizes this value
         * when a received close frame has no status body.
         */
        val NO_STATUS_RECEIVED = StatusCode(1005)

        /**
         * 1006 — Connection closed abnormally without a close frame.
         *
         * RFC 6455 §7.4.1: MUST NOT be sent on the wire. Surfaced when the TCP connection drops
         * without a close handshake.
         */
        val ABNORMAL_CLOSURE = StatusCode(1006)

        /** 1007 — Received payload not consistent with message type (e.g. invalid UTF-8). */
        val INVALID_FRAME_PAYLOAD_DATA = StatusCode(1007)

        /** 1008 — Message violates endpoint policy. */
        val POLICY_VIOLATION = StatusCode(1008)

        /** 1009 — Message too large for the endpoint to process. */
        val MESSAGE_TOO_BIG = StatusCode(1009)

        /** 1010 — Client expected the server to negotiate required extensions. */
        val MANDATORY_EXTENSION = StatusCode(1010)

        /** 1011 — Server encountered an unexpected condition. */
        val INTERNAL_ERROR = StatusCode(1011)

        /**
         * 1015 — TLS handshake failure.
         *
         * RFC 6455 §7.4.1: MUST NOT be sent on the wire. Defined here as a constant
         * for callers matching codes received from non-compliant peers; this library
         * does not synthesize it internally.
         */
        val TLS_HANDSHAKE = StatusCode(1015)
    }
}
