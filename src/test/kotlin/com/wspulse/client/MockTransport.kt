package com.wspulse.client

import io.ktor.websocket.CloseReason
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import io.ktor.websocket.Frame as WsFrame

/**
 * In-memory [Transport] for component tests.
 *
 * Incoming frames are injected via [injectText], [injectPong], etc.
 * Outgoing frames sent by the client are captured in [sent].
 */
internal class MockTransport : Transport {
    private val incomingChannel = Channel<WsFrame>(Channel.UNLIMITED)
    private val closedFlag = AtomicBoolean(false)

    /** All frames sent by the client through this transport. */
    val sent = CopyOnWriteArrayList<WsFrame>()

    /** Whether [close] has been called. */
    val isClosed: Boolean get() = closedFlag.get()

    override val incoming: ReceiveChannel<WsFrame> get() = incomingChannel

    override suspend fun send(frame: WsFrame) {
        if (closedFlag.get()) throw ClosedReceiveChannelException("transport closed")
        sent.add(frame)
    }

    override suspend fun close(reason: CloseReason) {
        if (closedFlag.compareAndSet(false, true)) {
            incomingChannel.close()
        }
    }

    // ── test injection helpers ──────────────────────────────────────────────

    /** Inject a text frame (simulates server sending a message). */
    fun injectText(data: String) {
        incomingChannel.trySend(WsFrame.Text(data))
    }

    /** Inject a pong frame (simulates server responding to ping). */
    fun injectPong() {
        incomingChannel.trySend(WsFrame.Pong(ByteArray(0)))
    }

    /** Close the incoming channel (simulates transport drop). */
    fun injectClose() {
        if (closedFlag.compareAndSet(false, true)) {
            incomingChannel.close()
        }
    }

    /** Close the incoming channel with an error (simulates transport error). */
    fun injectError(cause: Exception = Exception("mock transport error")) {
        if (closedFlag.compareAndSet(false, true)) {
            incomingChannel.close(cause)
        }
    }

    /**
     * Auto-respond to Ping frames with Pong frames.
     *
     * Returns a coroutine-friendly helper. Call [PongResponder.stop] to stop
     * responding (simulates pong timeout scenario).
     */
    fun autoPong(): PongResponder = PongResponder(this)
}

/**
 * Watches a [MockTransport]'s sent frames for Ping and auto-injects Pong.
 *
 * Implementation: polls [MockTransport.sent] for new Ping frames.
 * Not perfectly real-time, but sufficient for component tests where
 * exact timing is controlled.
 */
internal class PongResponder(
    private val transport: MockTransport,
) {
    private val active = AtomicBoolean(true)
    private val lastSeen = AtomicInteger(0)

    /** Check for new Ping frames and inject Pong responses. */
    fun tick() {
        if (!active.get()) return
        val frames = transport.sent
        val size = frames.size
        for (i in lastSeen.get() until size) {
            val frame = frames[i]
            if (frame is WsFrame.Ping && active.get()) {
                transport.injectPong()
            }
        }
        lastSeen.set(size)
    }

    /** Stop auto-responding to pings. */
    fun stop() {
        active.set(false)
    }
}

/**
 * [Dialer] for component tests that returns pre-configured transports.
 *
 * Each call to [dial] returns the next transport from [transports].
 * If a transport is wrapped in a [Result.failure], the dial throws.
 */
internal class MockDialer(
    private val transports: List<Result<MockTransport>>,
) : Dialer {
    private val index = AtomicInteger(0)

    /** Number of times [dial] has been called. */
    val dialCount: Int get() = index.get()

    /** URLs passed to each [dial] call, in order. */
    val dialedUrls = CopyOnWriteArrayList<String>()

    /** Headers passed to each [dial] call, in order. */
    val dialedHeaders = CopyOnWriteArrayList<Map<String, String>>()

    override suspend fun dial(
        url: String,
        headers: Map<String, String>,
    ): Transport {
        dialedUrls.add(url)
        dialedHeaders.add(headers)
        val i = index.getAndIncrement()
        if (i >= transports.size) {
            throw Exception("wspulse: no more mock transports (dial #$i)")
        }
        return transports[i].getOrThrow()
    }
}
