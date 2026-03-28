package com.wspulse.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for a wspulse WebSocket client, built via the DSL in
 * [WspulseClient.connect].
 *
 * All callback properties default to no-ops. Callbacks are invoked on an
 * internal coroutine — implementations must not block.
 */
class ClientConfig {
    /** Called for every application-level frame received from the server. */
    var onMessage: (Frame) -> Unit = {}

    /**
     * Called exactly once when the client permanently disconnects.
     *
     * A `null` argument means graceful close via [Client.close].
     * A non-null [WspulseException] indicates the reason for disconnection.
     */
    var onDisconnect: (WspulseException?) -> Unit = {}

    /**
     * Called after a successful reconnect when the new transport is ready.
     * Does not fire on the initial connection.
     * Replaces the former `onReconnect` callback.
     */
    var onTransportRestore: () -> Unit = {}

    /**
     * Called when the underlying transport drops unexpectedly.
     *
     * If auto-reconnect is enabled, the client will attempt to reconnect
     * after this callback returns. If disabled, [onDisconnect] follows.
     */
    var onTransportDrop: (Exception) -> Unit = {}

    /**
     * Auto-reconnect configuration. `null` disables auto-reconnect
     * (transport drop becomes a permanent disconnect).
     */
    var autoReconnect: AutoReconnectConfig? = null

    /** Heartbeat (ping/pong) configuration. */
    var heartbeat: HeartbeatConfig = HeartbeatConfig()

    /** Maximum time to wait for a write to complete before treating it as a transport failure. */
    var writeWait: Duration = 10.seconds

    /** Maximum incoming message size in bytes. Messages exceeding this are rejected. Set to 0 to disable size enforcement. */
    var maxMessageSize: Long = 1L shl 20 // 1 MiB

    /** Additional HTTP headers sent during the WebSocket handshake. */
    var dialHeaders: Map<String, String> = emptyMap()

    /** Codec used for frame serialisation. Defaults to [JsonCodec]. */
    var codec: Codec = JsonCodec

    /**
     * Capacity of the internal send buffer (number of frames).
     *
     * Must be between 1 and 4096 inclusive. Larger values allow more frames
     * to be queued during brief disconnections or bursty sends.
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

/**
 * Heartbeat (ping/pong) timing parameters.
 *
 * @param pingPeriod Interval between outgoing pings.
 * @param pongWait Maximum time to wait for a pong reply before treating the
 *   connection as dead.
 */
data class HeartbeatConfig(
    val pingPeriod: Duration = 20.seconds,
    val pongWait: Duration = 60.seconds,
)
