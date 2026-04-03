package com.wspulse.client

import io.ktor.websocket.CloseReason
import kotlinx.coroutines.channels.ReceiveChannel
import io.ktor.websocket.Frame as WsFrame

/**
 * Abstraction over the raw WebSocket session.
 *
 * Production code uses [RealTransport] (wraps Ktor [io.ktor.websocket.DefaultWebSocketSession]).
 * Tests inject a mock implementation to eliminate network I/O.
 *
 * Internal visibility -- not part of the public API.
 */
internal interface Transport {
    /** Channel of incoming WebSocket frames. */
    val incoming: ReceiveChannel<WsFrame>

    /** Send a WebSocket frame. */
    suspend fun send(frame: WsFrame)

    /** Close the transport with the given reason. */
    suspend fun close(reason: CloseReason = CloseReason(CloseReason.Codes.NORMAL, ""))
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
