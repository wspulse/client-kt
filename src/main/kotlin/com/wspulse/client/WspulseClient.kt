package com.wspulse.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.headers
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import io.ktor.websocket.Frame as WsFrame

/**
 * Public interface for the wspulse WebSocket client.
 *
 * Obtained by calling [WspulseClient.connect]. Both [send] and [close] are
 * safe to call from any coroutine or thread.
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
     * Suspends until all internal coroutines have exited. After this returns,
     * the client holds no background resources. Idempotent: calling more than
     * once is safe.
     *
     * Do not call `close()` synchronously from within any callback
     * ([ClientConfig.onMessage], [ClientConfig.onDisconnect], etc.); the
     * callback runs inside a tracked coroutine and waiting for it to exit
     * would deadlock. Launch a separate coroutine if closing from a callback
     * is required.
     */
    suspend fun close()

    /**
     * Completes when the client permanently disconnects.
     *
     * This includes an explicit [close] call, a server-side drop when
     * auto-reconnect is disabled, or max reconnect retries being exhausted.
     */
    val done: Deferred<Unit>
}

/** Internal send buffer capacity. Matches client-go (256). */
private const val SEND_BUFFER_SIZE = 256

/**
 * Internal client implementation.
 *
 * Lifecycle states (conceptual, not exposed):
 * - CONNECTED: WebSocket session is open, readLoop/writeLoop/pingLoop running.
 * - RECONNECTING: transport dropped, backoff + retry in progress.
 * - CLOSED: permanently disconnected, all resources released.
 */
class WspulseClient private constructor(
    private val url: String,
    private val config: ClientConfig,
    private val httpClient: HttpClient,
) : Client {

    companion object {
        private val logger = LoggerFactory.getLogger(WspulseClient::class.java)

        /**
         * Connect to a wspulse WebSocket server.
         *
         * If [ClientConfig.autoReconnect] is configured and the initial handshake
         * fails, the client enters RECONNECTING state and retries using the
         * configured backoff. Without autoReconnect, a failed initial handshake
         * throws immediately.
         *
         * @param url  WebSocket URL (e.g. `wss://host/ws`)
         * @param init DSL block to configure the client.
         * @return A [Client] in CONNECTED or RECONNECTING state.
         */
        suspend fun connect(url: String, init: ClientConfig.() -> Unit = {}): Client {
            val config = ClientConfig().apply(init)
            val httpClient = HttpClient(CIO) {
                install(WebSockets)
            }

            val client = WspulseClient(url, config, httpClient)

            if (config.autoReconnect == null) {
                // No reconnect: fail fast on initial dial failure.
                val session = client.dialOnce()
                client.startConnection(session)
            } else {
                try {
                    val session = client.dialOnce()
                    client.startConnection(session)
                } catch (e: Exception) {
                    // Initial dial failed — fire onTransportDrop and start reconnect.
                    config.onTransportDrop(e)
                    client.scope.launch { client.reconnectLoop() }
                }
            }

            return client
        }
    }

    /** Scope that owns all internal coroutines. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bounded send channel. [send] uses [Channel.trySend] (non-blocking). */
    private val sendChannel = Channel<ByteArray>(SEND_BUFFER_SIZE)

    /** Completes on permanent disconnect. */
    private val _done = CompletableDeferred<Unit>()
    override val done: Deferred<Unit> get() = _done

    /** Guards [shutdown] to fire exactly once. */
    private val shutdownOnce = AtomicBoolean(false)

    /** Whether [close] has been called. */
    private val closed = AtomicBoolean(false)

    /** Guards [handleTransportDrop] to prevent concurrent reconnect loops. */
    private val reconnecting = AtomicBoolean(false)

    /**
     * Job for the current connection's coroutines (readLoop, writeLoop,
     * pingLoop). Cancelled and replaced on each reconnect so old loops
     * stop before new ones start.
     */
    private var connectionJob: Job? = null

    /** Current WebSocket session. Guarded by [connectionJob] lifecycle. */
    @Volatile
    private var session: DefaultWebSocketSession? = null

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
            session?.close(CloseReason(CloseReason.Codes.NORMAL, ""))
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
    private suspend fun dialOnce(): DefaultWebSocketSession {
        return httpClient.webSocketSession(url) {
            headers {
                config.dialHeaders.forEach { (k, v) -> append(k, v) }
            }
        }
    }

    /**
     * Start readLoop, writeLoop, and pingLoop for a new session.
     *
     * Previous connection coroutines (if any) are cancelled first.
     */
    private fun startConnection(ws: DefaultWebSocketSession) {
        connectionJob?.cancel()
        val oldSession = session
        session = ws

        // Best-effort close the previous WebSocket session.
        if (oldSession != null) {
            scope.launch {
                try {
                    oldSession.close(CloseReason(CloseReason.Codes.GOING_AWAY, "reconnecting"))
                } catch (_: Exception) { /* already closed */ }
            }
        }

        val job = Job(scope.coroutineContext[Job])
        connectionJob = job
        val connScope = CoroutineScope(scope.coroutineContext + job)

        val dropped = CompletableDeferred<Exception?>()

        connScope.launch { readLoop(ws, dropped) }
        connScope.launch { writeLoop(ws) }
        connScope.launch { pingLoop(ws) }

        // Monitor for transport drop.
        scope.launch {
            val cause: Exception?
            try {
                cause = dropped.await()
            } catch (_: CancellationException) {
                return@launch
            }
            handleTransportDrop(cause)
        }
    }

    /**
     * Read incoming WebSocket frames, decode, and dispatch to onMessage.
     *
     * Completes [dropped] when the session's incoming channel closes
     * (transport drop).
     */
    private suspend fun readLoop(
        ws: DefaultWebSocketSession,
        dropped: CompletableDeferred<Exception?>,
    ) {
        var readError: Exception? = null
        try {
            for (wsFrame in ws.incoming) {
                if (!scope.isActive) return

                val data: ByteArray = when (wsFrame) {
                    is WsFrame.Text -> wsFrame.data
                    is WsFrame.Binary -> wsFrame.data
                    is WsFrame.Pong -> {
                        resetPongDeadline(ws)
                        continue
                    }
                    else -> continue
                }

                // maxMessageSize enforcement.
                if (config.maxMessageSize > 0 && data.size > config.maxMessageSize) {
                    logger.warn(
                        "wspulse/client: message too large ({} > {}), closing",
                        data.size, config.maxMessageSize,
                    )
                    try {
                        ws.close(CloseReason(CloseReason.Codes.TOO_BIG, "message too large"))
                    } catch (_: Exception) { /* already closing */ }
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
    private suspend fun writeLoop(ws: DefaultWebSocketSession) {
        try {
            for (data in sendChannel) {
                if (!scope.isActive) return

                val wsFrame = when (config.codec.frameType) {
                    FrameType.TEXT -> WsFrame.Text(String(data, Charsets.UTF_8))
                    FrameType.BINARY -> WsFrame.Binary(true, data)
                }

                try {
                    withTimeout(config.writeWait) {
                        ws.send(wsFrame)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException && !scope.isActive) throw e
                    logger.warn("wspulse/client: write failed", e)
                    try {
                        ws.close(CloseReason(CloseReason.Codes.GOING_AWAY, "write error"))
                    } catch (_: Exception) { /* already closing */ }
                    return
                }
            }
        } catch (_: CancellationException) {
            // Scope cancelled — normal shutdown.
        }
    }

    // ── internal: heartbeat ─────────────────────────────────────────────────

    /** Job for the current pong deadline timer. Reset on each Pong received. */
    @Volatile
    private var pongDeadlineJob: Job? = null

    /**
     * Send WebSocket Ping frames at [HeartbeatConfig.pingPeriod] intervals.
     */
    private suspend fun pingLoop(ws: DefaultWebSocketSession) {
        val pingPeriod = config.heartbeat.pingPeriod

        // Send initial ping and start pong deadline.
        try {
            ws.send(WsFrame.Ping(ByteArray(0)))
            resetPongDeadline(ws)
        } catch (_: Exception) {
            return
        }

        try {
            while (scope.isActive) {
                delay(pingPeriod)
                ws.send(WsFrame.Ping(ByteArray(0)))
            }
        } catch (_: CancellationException) {
            // Normal shutdown.
        } catch (e: Exception) {
            logger.debug("wspulse/client: pingLoop error", e)
        }
    }

    /**
     * Reset the pong deadline timer. Called when a Pong frame is received.
     *
     * If the timer fires (no Pong within [HeartbeatConfig.pongWait]), the
     * session is closed, which triggers a transport drop.
     */
    private fun resetPongDeadline(ws: DefaultWebSocketSession) {
        pongDeadlineJob?.cancel()
        pongDeadlineJob = scope.launch {
            delay(config.heartbeat.pongWait)
            logger.warn("wspulse/client: pong timeout, closing connection")
            try {
                ws.close(CloseReason(CloseReason.Codes.GOING_AWAY, "pong timeout"))
            } catch (_: Exception) { /* already closing */ }
        }
    }

    // ── internal: reconnect ─────────────────────────────────────────────────

    /**
     * Handle an unexpected transport drop.
     *
     * If auto-reconnect is enabled, starts the reconnect loop.
     * Otherwise, transitions to CLOSED immediately.
     */
    private fun handleTransportDrop(cause: Exception?) {
        if (closed.get()) return
        if (!reconnecting.compareAndSet(false, true)) return

        // Cancel current connection coroutines.
        connectionJob?.cancel()
        pongDeadlineJob?.cancel()

        val err = cause ?: Exception("wspulse: transport closed unexpectedly")
        config.onTransportDrop(err)

        if (config.autoReconnect != null) {
            scope.launch { reconnectLoop() }
        } else {
            shutdown(ConnectionLostException())
        }
    }

    /**
     * Reconnect loop with exponential backoff.
     *
     * Stops when:
     * - A reconnect attempt succeeds → new connection started.
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
            val delayDuration = backoff(attempt, rc.baseDelay, rc.maxDelay)
            logger.debug(
                "wspulse/client: backoff attempt={} delay={}",
                attempt, delayDuration,
            )
            try {
                delay(delayDuration)
            } catch (_: CancellationException) {
                return // close() was called.
            }

            if (closed.get()) return

            // Fire onReconnect before the dial attempt.
            config.onReconnect(attempt)

            // Attempt to dial.
            try {
                val newSession = dialOnce()

                // Check if close() was called during dial.
                if (closed.get()) {
                    try {
                        newSession.close(CloseReason(CloseReason.Codes.NORMAL, ""))
                    } catch (_: Exception) { /* ignore */ }
                    return
                }

                // Start new connection loops.
                startConnection(newSession)
                reconnecting.set(false)
                logger.info("wspulse/client: reconnected attempt={} url={}", attempt, url)
                return // Successfully reconnected.
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
     * @param err `null` for clean close, a [WspulseException] for abnormal
     *   disconnect.
     */
    private fun shutdown(err: WspulseException?) {
        if (!shutdownOnce.compareAndSet(false, true)) return

        logger.debug("wspulse/client: shutdown err={}", err)

        // Cancel all coroutines.
        connectionJob?.cancel()
        pongDeadlineJob?.cancel()

        // Close the HTTP client (releases CIO resources).
        try {
            httpClient.close()
        } catch (_: Exception) { /* ignore */ }

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
