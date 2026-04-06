package com.wspulse.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.headers
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import io.ktor.websocket.Frame as WsFrame

/**
 * Public interface for the wspulse WebSocket client.
 *
 * Obtained by calling [WspulseClient.connect]. Both [send] and [close] are safe to call from any
 * coroutine or thread.
 */
interface Client {
    /**
     * Enqueue a [Frame] for delivery to the server.
     *
     * Non-blocking. This function does not suspend.
     *
     * @throws ConnectionClosedException if the client is closed.
     * @throws SendBufferFullException if the internal send buffer is full.
     */
    fun send(frame: Frame)

    /**
     * Permanently terminate the connection and stop any reconnect loop.
     *
     * Suspends until all internal coroutines have exited. After this returns, the client holds no
     * background resources. Idempotent: calling more than once is safe.
     *
     * Do not call `close()` synchronously from within any callback ([ClientConfig.onMessage],
     * [ClientConfig.onDisconnect], etc.); the callback runs inside a tracked coroutine and waiting
     * for it to exit would deadlock. Launch a separate coroutine if closing from a callback is
     * required.
     */
    suspend fun close()

    /**
     * Completes when the client permanently disconnects.
     *
     * This includes an explicit [close] call, a server-side drop when auto-reconnect is disabled,
     * or max reconnect retries being exhausted.
     */
    val done: Deferred<Unit>
}

// Configuration upper bounds — matches client-go validation ceilings.
private const val MAX_SEND_BUFFER_SIZE = 4096
private val MAX_PING_PERIOD = 1.minutes
private val MAX_PONG_WAIT = 2.minutes
private val MAX_WRITE_WAIT = 30.seconds
private const val MAX_MSG_SIZE_BYTES = 64L shl 20 // 64 MiB
private val MAX_BASE_DELAY = 1.minutes
private val MAX_DELAY_LIMIT = 5.minutes
private const val MAX_RETRIES_LIMIT = 32

/**
 * Internal client implementation.
 *
 * Lifecycle states (conceptual, not exposed):
 * - CONNECTED: WebSocket session is open, readLoop/writeLoop/pingLoop running.
 * - RECONNECTING: transport dropped, backoff + retry in progress.
 * - CLOSED: permanently disconnected, all resources released.
 */
class WspulseClient
    private constructor(
        private val url: String,
        private val config: ClientConfig,
        private val dialer: Dialer,
        private val onShutdown: () -> Unit,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        private val random: Random = Random.Default,
    ) : Client {
        companion object {
            private val logger = LoggerFactory.getLogger(WspulseClient::class.java)

            /**
             * Connect to a wspulse WebSocket server.
             *
             * The initial dial is always fatal: if the handshake fails, the exception is propagated to
             * the caller regardless of whether [ClientConfig.autoReconnect] is configured. No callbacks
             * fire and no [Client] is returned to the caller. Auto-reconnect only activates after a
             * successful initial connection subsequently drops.
             *
             * @param url WebSocket or HTTP URL (e.g. `wss://host/ws`). `http://` and `https://`
             *   schemes are auto-converted to `ws://` and `wss://` respectively.
             * @param init DSL block to configure the client.
             * @return A [Client] whose initial WebSocket handshake has completed successfully.
             *   The underlying transport is connected on a best-effort basis and may transition
             *   to reconnecting or closed state according to the configured lifecycle.
             */
            suspend fun connect(
                url: String,
                init: ClientConfig.() -> Unit = {},
            ): Client {
                val normalizedUrl = normalizeScheme(url)
                val config = ClientConfig().apply(init)
                validateConfig(config)
                val httpClient = HttpClient(CIO) { install(WebSockets) }

                val dialer =
                    Dialer { dialUrl, dialHeaders ->
                        val session =
                            httpClient.webSocketSession(dialUrl) {
                                headers { dialHeaders.forEach { (k, v) -> append(k, v) } }
                            }
                        RealTransport(session, CoroutineScope(session.coroutineContext))
                    }

                return connectInternal(
                    normalizedUrl,
                    config,
                    dialer,
                    onShutdown = { httpClient.close() },
                )
            }

            /**
             * Internal connect for testing — accepts a custom [Dialer].
             *
             * Not part of the public API. Callers must validate config and normalize the URL before
             * calling.
             */
            @JvmSynthetic
            internal suspend fun connectInternal(
                url: String,
                config: ClientConfig,
                dialer: Dialer,
                onShutdown: () -> Unit = {},
                dispatcher: CoroutineDispatcher = Dispatchers.IO,
                random: Random = Random.Default,
            ): Client {
                val client = WspulseClient(url, config, dialer, onShutdown, dispatcher, random)

                try {
                    val transport = client.dialOnce()
                    val dropped = client.startConnection(transport)
                    // Monitor for transport drop -> reconnect or permanent disconnect.
                    client.scope.launch {
                        val cause: Exception?
                        try {
                            cause = dropped.await()
                        } catch (_: CancellationException) {
                            return@launch
                        }
                        client.handleTransportDrop(cause)
                    }
                } catch (e: Exception) {
                    // Release resources before propagating.
                    client.scope.coroutineContext[Job]?.cancel()
                    client.onShutdown()
                    throw e
                }

                return client
            }
        }

        /** Scope that owns all internal coroutines. */
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)

        /** Bounded send channel. [send] uses [Channel.trySend] (non-blocking). */
        private val sendChannel = Channel<ByteArray>(config.sendBufferSize)

        /** Completes on permanent disconnect. */
        private val _done = CompletableDeferred<Unit>()
        override val done: Deferred<Unit>
            get() = _done

        /** Guards [shutdown] to fire exactly once. */
        private val shutdownOnce = AtomicBoolean(false)

        /** Whether [close] has been called. */
        private val closed = AtomicBoolean(false)

        /** Guards [handleTransportDrop] to prevent concurrent reconnect loops. */
        private val reconnecting = AtomicBoolean(false)

        /**
         * Job for the current connection's coroutines (readLoop, writeLoop, pingLoop). Cancelled and
         * replaced on each reconnect so old loops stop before new ones start.
         */
        private var connectionJob: Job? = null

        /** Current transport. Guarded by [connectionJob] lifecycle. */
        @Volatile private var transport: Transport? = null

        // ── public API ──────────────────────────────────────────────────────────

        override fun send(frame: Frame) {
            if (closed.get() || _done.isCompleted) throw ConnectionClosedException()

            val data = config.codec.encode(frame)

            val result = sendChannel.trySend(data)
            if (result.isFailure) {
                if (_done.isCompleted) throw ConnectionClosedException()
                throw SendBufferFullException()
            }
        }

        override suspend fun close() {
            if (!closed.compareAndSet(false, true)) {
                // Already closing/closed — just wait for completion.
                _done.await()
                return
            }

            logger.info("wspulse/client: closing url={}", url)

            // Send WebSocket close frame (best-effort).
            try {
                transport?.close(TransportCloseReason.NORMAL)
            } catch (_: Exception) {
                // Already closed — ignore.
            }

            // Cancel the scope and wait for all child coroutines to finish.
            scope.coroutineContext[Job]?.cancelAndJoin()

            // Transition to CLOSED.
            shutdown(null)
        }

        // ── internal: connection lifecycle ───────────────────────────────────────

        /**
         * Dial the WebSocket server once.
         *
         * @throws Exception on connection failure.
         */
        private suspend fun dialOnce(): Transport = dialer.dial(url, config.dialHeaders)

        /**
         * Start readLoop, writeLoop, and pingLoop for a new transport.
         *
         * Previous connection coroutines (if any) are cancelled first.
         *
         * @return the [CompletableDeferred] that completes when the transport drops.
         */
        private fun startConnection(ws: Transport): CompletableDeferred<Exception?> {
            connectionJob?.cancel()
            val oldTransport = transport
            transport = ws

            // Best-effort close the previous transport.
            if (oldTransport != null) {
                scope.launch {
                    try {
                        oldTransport.close(TransportCloseReason.RECONNECTING)
                    } catch (_: Exception) {
                        // already closed
                    }
                }
            }

            val job = Job(scope.coroutineContext[Job])
            connectionJob = job
            val connScope = CoroutineScope(scope.coroutineContext + job)

            val dropped = CompletableDeferred<Exception?>()

            connScope.launch { readLoop(ws, dropped) }
            connScope.launch { writeLoop(ws, dropped) }
            connScope.launch { pingLoop(ws, dropped) }

            return dropped
        }

        /**
         * Read incoming WebSocket frames, decode, and dispatch to onMessage.
         *
         * Completes [dropped] when the transport's incoming channel closes (transport drop).
         */
        private suspend fun readLoop(
            ws: Transport,
            dropped: CompletableDeferred<Exception?>,
        ) {
            var readError: Exception? = null
            try {
                for (wsFrame in ws.incoming) {
                    if (!scope.isActive) return

                    val data: ByteArray =
                        when (wsFrame) {
                            is TransportFrame.Text -> wsFrame.data.toByteArray(Charsets.UTF_8)
                            is TransportFrame.Binary -> wsFrame.data
                            is TransportFrame.Pong -> {
                                resetPongDeadline(ws)
                                continue
                            }
                            else -> continue
                        }

                    // maxMessageSize enforcement.
                    if (config.maxMessageSize > 0 && data.size.toLong() > config.maxMessageSize) {
                        logger.warn(
                            "wspulse/client: message too large ({} > {}), closing",
                            data.size,
                            config.maxMessageSize,
                        )
                        try {
                            ws.close(TransportCloseReason.MESSAGE_TOO_LARGE)
                        } catch (_: Exception) {
                            // already closing
                        }
                        break
                    }

                    val frame: Frame
                    try {
                        frame = config.codec.decode(data)
                    } catch (e: Exception) {
                        logger.warn("wspulse/client: decode failed, frame dropped", e)
                        continue
                    }
                    try {
                        config.onMessage(frame)
                    } catch (e: Exception) {
                        logger.warn("wspulse/client: onMessage callback threw", e)
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
                // Normal: session closed.
            } catch (_: CancellationException) {
                throw CancellationException("readLoop cancelled")
            } catch (e: Exception) {
                readError = e
                logger.debug("wspulse/client: readLoop error", e)
            } finally {
                dropped.complete(readError)
            }
        }

        /**
         * Consume the send channel and write to the WebSocket.
         *
         * Each write is wrapped in [withTimeout] using [ClientConfig.writeWait].
         */
        private suspend fun writeLoop(
            ws: Transport,
            dropped: CompletableDeferred<Exception?>,
        ) {
            try {
                for (data in sendChannel) {
                    if (!scope.isActive) return

                    val wsFrame =
                        when (config.codec.frameType) {
                            FrameType.TEXT -> TransportFrame.Text(String(data, Charsets.UTF_8))
                            FrameType.BINARY -> TransportFrame.Binary(data)
                        }

                    try {
                        withTimeout(config.writeWait) { ws.send(wsFrame) }
                    } catch (e: TimeoutCancellationException) {
                        // writeWait timeout — treat as write failure.
                        logger.warn("wspulse/client: write failed", e)
                        dropped.complete(e)
                        try {
                            ws.close(TransportCloseReason.WRITE_ERROR)
                        } catch (_: Exception) {
                            // already closing
                        }
                        return
                    } catch (e: CancellationException) {
                        // Coroutine cancelled (e.g. connectionJob cancel during reconnect) — propagate
                        // so the outer catch handles it as a clean shutdown, not a write failure.
                        throw e
                    } catch (e: Exception) {
                        logger.warn("wspulse/client: write failed", e)
                        dropped.complete(e)
                        try {
                            ws.close(TransportCloseReason.WRITE_ERROR)
                        } catch (_: Exception) {
                            // already closing
                        }
                        return
                    }
                }
            } catch (_: CancellationException) {
                // Scope cancelled — normal shutdown.
            }
        }

        // ── internal: heartbeat ─────────────────────────────────────────────────

        /** Job for the current pong deadline timer. Reset on each Pong received. */
        @Volatile private var pongDeadlineJob: Job? = null

        /** Send WebSocket Ping frames at [HeartbeatConfig.pingPeriod] intervals. */
        private suspend fun pingLoop(
            ws: Transport,
            dropped: CompletableDeferred<Exception?>,
        ) {
            val pingPeriod = config.heartbeat.pingPeriod

            // Send initial ping and start pong deadline.
            try {
                ws.send(TransportFrame.Ping(ByteArray(0)))
                resetPongDeadline(ws)
            } catch (e: Exception) {
                dropped.complete(e)
                return
            }

            try {
                while (scope.isActive) {
                    delay(pingPeriod)
                    ws.send(TransportFrame.Ping(ByteArray(0)))
                }
            } catch (_: CancellationException) {
                // Normal shutdown.
            } catch (e: Exception) {
                dropped.complete(e)
                logger.debug("wspulse/client: pingLoop error", e)
            }
        }

        /**
         * Reset the pong deadline timer. Called when a Pong frame is received.
         *
         * If the timer fires (no Pong within [HeartbeatConfig.pongWait]), the transport is closed,
         * which triggers a transport drop.
         */
        private fun resetPongDeadline(ws: Transport) {
            pongDeadlineJob?.cancel()
            pongDeadlineJob =
                scope.launch {
                    delay(config.heartbeat.pongWait)
                    logger.warn("wspulse/client: pong timeout, closing connection")
                    try {
                        ws.close(TransportCloseReason.PONG_TIMEOUT)
                    } catch (_: Exception) {
                        // already closing
                    }
                }
        }

        // ── internal: reconnect ─────────────────────────────────────────────────

        /**
         * Handle an unexpected transport drop.
         *
         * If auto-reconnect is enabled, starts the reconnect loop. Otherwise, transitions to CLOSED
         * immediately.
         */
        private fun handleTransportDrop(cause: Exception?) {
            if (closed.get()) return
            if (!reconnecting.compareAndSet(false, true)) return

            // Cancel current connection coroutines.
            connectionJob?.cancel()
            pongDeadlineJob?.cancel()

            val err = cause ?: Exception("wspulse: transport closed unexpectedly")
            try {
                config.onTransportDrop(err)
            } catch (e: Exception) {
                logger.warn("wspulse/client: onTransportDrop callback threw", e)
            }

            if (config.autoReconnect != null) {
                scope.launch { reconnectLoop() }
            } else {
                shutdown(ConnectionLostException(err))
            }
        }

        /**
         * Reconnect loop with exponential backoff.
         *
         * Persistent loop — after a successful reconnect, waits for the new connection to drop before
         * retrying. This eliminates the race window where a drop between [startConnection] and
         * `reconnecting.set(false)` could be silently ignored.
         *
         * Stops when:
         * - Max retries exhausted → CLOSED with [RetriesExhaustedException].
         * - [close] called → CLOSED with `null`.
         */
        private suspend fun reconnectLoop() {
            val rc = config.autoReconnect ?: return
            var attempt = 0

            while (scope.isActive && !closed.get()) {
                // Check max retries.
                if (rc.maxRetries > 0 && attempt >= rc.maxRetries) {
                    shutdown(RetriesExhaustedException(attempt))
                    return
                }

                // Backoff delay.
                val delayDuration = backoff(attempt, rc.baseDelay, rc.maxDelay, random)
                logger.debug(
                    "wspulse/client: backoff attempt={} delay={}",
                    attempt,
                    delayDuration,
                )
                try {
                    delay(delayDuration)
                } catch (_: CancellationException) {
                    return // close() was called.
                }

                if (closed.get()) return

                // Attempt to dial.
                try {
                    val newTransport = dialOnce()

                    // Check if close() was called during dial.
                    if (closed.get()) {
                        try {
                            newTransport.close(TransportCloseReason.NORMAL)
                        } catch (_: Exception) {
                            // ignore
                        }
                        return
                    }

                    // Start new connection loops and wait for drop.
                    val dropped = startConnection(newTransport)
                    reconnecting.set(false)
                    logger.info("wspulse/client: reconnected attempt={} url={}", attempt, url)

                    if (closed.get()) return

                    try {
                        config.onTransportRestore()
                    } catch (e: Exception) {
                        logger.warn("wspulse/client: onTransportRestore callback threw", e)
                    }

                    // Wait for this connection to drop before looping.
                    val dropCause: Exception?
                    try {
                        dropCause = dropped.await()
                    } catch (_: CancellationException) {
                        return // close() was called.
                    }
                    if (closed.get()) return

                    // Prepare for next reconnect cycle.
                    reconnecting.set(true)
                    connectionJob?.cancel()
                    pongDeadlineJob?.cancel()
                    val dropErr = dropCause ?: Exception("wspulse: transport closed unexpectedly")
                    try {
                        config.onTransportDrop(dropErr)
                    } catch (e: Exception) {
                        logger.warn("wspulse/client: onTransportDrop callback threw", e)
                    }
                    attempt = 0
                } catch (e: Exception) {
                    logger.debug("wspulse/client: dial failed attempt={}", attempt, e)
                    attempt++
                }
            }
        }

        // ── internal: shutdown ──────────────────────────────────────────────────

        /**
         * Transition to CLOSED state. Releases all resources.
         *
         * @param err `null` for clean close, a [WspulseException] for abnormal disconnect.
         */
        private fun shutdown(err: WspulseException?) {
            if (!shutdownOnce.compareAndSet(false, true)) return

            logger.debug("wspulse/client: shutdown err={}", err)

            // Cancel the scope so all child coroutines exit.
            scope.coroutineContext[Job]?.cancel()
            connectionJob?.cancel()
            pongDeadlineJob?.cancel()

            // Release external resources (e.g. CIO HttpClient).
            try {
                onShutdown()
            } catch (_: Exception) {
                // ignore
            }

            // On clean close while CONNECTED, fire onTransportDrop(null)
            // before onDisconnect. Unexpected drops already fired in handleTransportDrop.
            // When close() is called while reconnecting, do not fire again.
            if (err == null && !reconnecting.get()) {
                try {
                    config.onTransportDrop(null)
                } catch (e: Exception) {
                    logger.warn("wspulse/client: onTransportDrop callback threw", e)
                }
            }

            // Fire onDisconnect exactly once.
            try {
                config.onDisconnect(err)
            } catch (e: Exception) {
                logger.warn("wspulse/client: onDisconnect callback threw", e)
            }

            // Resolve done.
            _done.complete(Unit)
        }
    }

/**
 * [Transport] backed by a Ktor [DefaultWebSocketSession].
 *
 * This is the only class that converts between library-owned [TransportFrame] and Ktor's frame
 * types. All Ktor WebSocket frame imports are confined here and in the production dialer lambda
 * above.
 *
 * Internal visibility — not part of the public API.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class RealTransport(
    private val session: DefaultWebSocketSession,
    scope: CoroutineScope,
) : Transport {
    override val incoming: ReceiveChannel<TransportFrame> =
        scope.produce {
            for (frame in session.incoming) {
                val mapped =
                    when (frame) {
                        is WsFrame.Text -> TransportFrame.Text(frame.readText())
                        is WsFrame.Binary -> TransportFrame.Binary(frame.readBytes())
                        is WsFrame.Ping -> TransportFrame.Ping(frame.data)
                        is WsFrame.Pong -> TransportFrame.Pong(frame.data)
                        is WsFrame.Close -> {
                            val reason = frame.readBytes()
                            val code =
                                if (reason.size >= 2) {
                                    (
                                        ((reason[0].toInt() and 0xFF) shl 8) or
                                            (reason[1].toInt() and 0xFF)
                                    ).toShort()
                                } else {
                                    TransportCloseReason.NO_STATUS_RECEIVED.code
                                }
                            val msg =
                                if (reason.size > 2) {
                                    String(reason, 2, reason.size - 2, Charsets.UTF_8)
                                } else {
                                    ""
                                }
                            TransportFrame.Close(code, msg)
                        }
                    }
                send(mapped)
            }
        }

    override suspend fun send(frame: TransportFrame) {
        when (frame) {
            is TransportFrame.Text -> session.send(frame.data)
            is TransportFrame.Binary -> session.send(WsFrame.Binary(true, frame.data))
            is TransportFrame.Ping -> session.send(WsFrame.Ping(frame.data))
            is TransportFrame.Pong -> session.send(WsFrame.Pong(frame.data))
            is TransportFrame.Close -> session.close(CloseReason(frame.code, frame.reason))
        }
    }

    override suspend fun close(reason: TransportCloseReason) = session.close(CloseReason(reason.code, reason.reason))
}

/**
 * Normalize the URL scheme for WebSocket connections.
 *
 * Converts `http://` to `ws://` and `https://` to `wss://`. `ws://` and `wss://` pass through
 * unchanged. Any other scheme (or missing scheme) throws [IllegalArgumentException].
 *
 * Validates explicitly because Ktor's error messages for invalid schemes are generic and unhelpful.
 */
internal fun normalizeScheme(url: String): String {
    val lower = url.lowercase(Locale.ROOT)
    return when {
        lower.startsWith("https://") -> "wss://" + url.substring("https://".length)
        lower.startsWith("http://") -> "ws://" + url.substring("http://".length)
        lower.startsWith("wss://") || lower.startsWith("ws://") -> url
        else -> {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd > 0) {
                throw IllegalArgumentException(
                    "wspulse: unsupported url scheme \"${url.substring(0, schemeEnd)}\"," +
                        " use ws://, wss://, http://, or https://",
                )
            }
            throw IllegalArgumentException(
                "wspulse: url must include scheme (ws://, wss://, http://, or https://)",
            )
        }
    }
}

/**
 * Validate [ClientConfig] values against upper bounds.
 *
 * Matches client-go's fail-fast validation. Called before any resources are allocated so invalid
 * config never leaks an [HttpClient].
 */
private fun validateConfig(config: ClientConfig) {
    require(config.sendBufferSize >= 1) { "wspulse: sendBufferSize must be at least 1" }
    require(config.sendBufferSize <= MAX_SEND_BUFFER_SIZE) {
        "wspulse: sendBufferSize exceeds maximum ($MAX_SEND_BUFFER_SIZE)"
    }

    require(config.maxMessageSize >= 0) { "wspulse: maxMessageSize must be non-negative" }
    require(config.maxMessageSize <= MAX_MSG_SIZE_BYTES) {
        "wspulse: maxMessageSize exceeds maximum (64 MiB)"
    }
    require(config.writeWait.isPositive()) { "wspulse: writeWait must be positive" }
    require(config.writeWait <= MAX_WRITE_WAIT) { "wspulse: writeWait exceeds maximum (30s)" }

    val hb = config.heartbeat
    require(hb.pingPeriod.isPositive()) { "wspulse: heartbeat.pingPeriod must be positive" }
    require(hb.pingPeriod <= MAX_PING_PERIOD) {
        "wspulse: heartbeat.pingPeriod exceeds maximum (1m)"
    }
    require(hb.pongWait.isPositive()) { "wspulse: heartbeat.pongWait must be positive" }
    require(hb.pongWait <= MAX_PONG_WAIT) { "wspulse: heartbeat.pongWait exceeds maximum (2m)" }
    require(hb.pingPeriod < hb.pongWait) {
        "wspulse: heartbeat.pingPeriod must be strictly less than heartbeat.pongWait"
    }

    config.autoReconnect?.let { rc ->
        require(rc.maxRetries >= 0) { "wspulse: autoReconnect.maxRetries must be non-negative" }
        require(rc.baseDelay.isPositive()) { "wspulse: autoReconnect.baseDelay must be positive" }
        require(rc.baseDelay <= MAX_BASE_DELAY) {
            "wspulse: autoReconnect.baseDelay exceeds maximum (1m)"
        }
        require(rc.maxDelay >= rc.baseDelay) {
            "wspulse: autoReconnect.maxDelay must be >= autoReconnect.baseDelay"
        }
        require(rc.maxDelay <= MAX_DELAY_LIMIT) {
            "wspulse: autoReconnect.maxDelay exceeds maximum (5m)"
        }
        if (rc.maxRetries > 0) {
            require(rc.maxRetries <= MAX_RETRIES_LIMIT) {
                "wspulse: autoReconnect.maxRetries exceeds maximum (32)"
            }
        }
    }
}
