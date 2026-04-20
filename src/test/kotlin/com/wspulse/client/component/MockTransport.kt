package com.wspulse.client.component

import com.wspulse.client.Dialer
import com.wspulse.client.Transport
import com.wspulse.client.TransportCloseReason
import com.wspulse.client.TransportFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory [Transport] for component tests.
 *
 * Incoming frames are injected via [injectText], [injectClose], etc.
 * Outgoing frames sent by the client are captured in [sent].
 */
internal class MockTransport : Transport {
    private val incomingChannel = Channel<TransportFrame>(Channel.UNLIMITED)
    private val closedFlag = AtomicBoolean(false)

    /** All frames sent by the client through this transport. */
    val sent = CopyOnWriteArrayList<TransportFrame>()

    /** Whether [close] has been called. */
    val isClosed: Boolean get() = closedFlag.get()

    override val incoming: ReceiveChannel<TransportFrame> get() = incomingChannel

    override suspend fun send(frame: TransportFrame) {
        if (closedFlag.get()) throw IllegalStateException("transport closed")
        sent.add(frame)
    }

    override suspend fun close(reason: TransportCloseReason) {
        if (closedFlag.compareAndSet(false, true)) {
            incomingChannel.close()
        }
    }

    // ── test injection helpers ──────────────────────────────────────────────

    /** Inject a text message (simulates server sending a message). */
    fun injectText(data: String) {
        incomingChannel.trySend(TransportFrame.Text(data)).getOrThrow()
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
