package com.wspulse.client

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Abstraction over the raw WebSocket session.
 *
 * Production code uses [RealTransport] (wraps Ktor's DefaultWebSocketSession).
 * Tests inject a mock implementation to eliminate network I/O.
 *
 * All frame types use library-owned [TransportFrame] — no transport library
 * types leak through this interface.
 *
 * Internal visibility -- not part of the public API.
 */
internal interface Transport {
    /** Channel of incoming WebSocket frames. */
    val incoming: ReceiveChannel<TransportFrame>

    /** Send a WebSocket frame. */
    suspend fun send(frame: TransportFrame)

    /** Close the transport with the given reason. */
    suspend fun close(reason: TransportCloseReason = TransportCloseReason.NORMAL)
}

/**
 * Factory for creating [Transport] instances.
 *
 * Production code uses a dialer backed by Ktor's [io.ktor.client.HttpClient].
 * Tests inject a mock dialer that returns pre-configured transports.
 *
 * Internal visibility -- not part of the public API.
 */
internal fun interface Dialer {
    /** Open a WebSocket connection and return a [Transport]. */
    suspend fun dial(
        url: String,
        headers: Map<String, String>,
    ): Transport
}
