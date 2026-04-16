package com.wspulse.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for a wspulse WebSocket client, built via the DSL in [WspulseClient.connect].
 *
 * All callback properties default to no-ops. Callbacks may be invoked on an internal coroutine
 * (transport drop, reconnect) or on the caller's coroutine during user-initiated shutdown
 * (e.g. [Client.close]). Implementations must not block.
 */
class ClientConfig {
    /** Called for every application-level frame received from the server. */
    var onMessage: (Frame) -> Unit = {}

    /**
     * Called exactly once when the client permanently disconnects.
     *
     * A `null` argument means graceful close via [Client.close]. A non-null [WspulseException]
     * indicates the reason for disconnection.
     */
    var onDisconnect: (WspulseException?) -> Unit = {}

    /**
     * Called after a successful reconnect when the new transport is ready. Does not fire on the
     * initial connection. Replaces the former `onReconnect` callback.
     */
    var onTransportRestore: () -> Unit = {}

    /**
     * Called each time the underlying transport closes.
     *
     * Fires with `null` on a clean [Client.close] call, or with the transport error on unexpected
     * drops. When [Client.close] is called while reconnecting, this callback does not fire again.
     */
    var onTransportDrop: (Exception?) -> Unit = {}

    /**
     * Auto-reconnect configuration. `null` disables auto-reconnect (transport drop becomes a
     * permanent disconnect).
     */
    var autoReconnect: AutoReconnectConfig? = null

    /** Maximum time to wait for a write to complete before treating it as a transport failure. */
    var writeWait: Duration = 10.seconds

    /**
     * Maximum incoming message size in bytes. Messages exceeding this are rejected. Set to 0 to
     * disable size enforcement.
     */
    var maxMessageSize: Long = 1L shl 20 // 1 MiB

    /** Additional HTTP headers sent during the WebSocket handshake. */
    var dialHeaders: Map<String, String> = emptyMap()

    /** Codec used for frame serialisation. Defaults to [JsonCodec]. */
    var codec: Codec = JsonCodec

    /**
     * Capacity of the internal send buffer (number of frames).
     *
     * Must be between 1 and 4096 inclusive. Larger values allow more frames to be queued during
     * brief disconnections or bursty sends.
     */
    var sendBufferSize: Int = 256
}

/**
 * Auto-reconnect parameters.
 *
 * @param maxRetries Maximum reconnection attempts. 0 means unlimited.
 * @param baseDelay Base delay for exponential backoff.
 * @param maxDelay Upper bound for backoff delay.
 */
data class AutoReconnectConfig(
    val maxRetries: Int = 0,
    val baseDelay: Duration = 1.seconds,
    val maxDelay: Duration = 30.seconds,
)
