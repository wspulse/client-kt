package com.wspulse.client

/**
 * Library-owned WebSocket frame abstraction.
 *
 * Decouples internal code from the transport library's (Ktor) frame types.
 * Only [RealTransport] converts between [TransportFrame] and Ktor frames.
 *
 * Internal visibility — not part of the public API.
 */
internal sealed class TransportFrame {
    /** UTF-8 text payload. */
    data class Text(
        val data: String,
    ) : TransportFrame()

    /** Raw binary payload. */
    class Binary(
        val data: ByteArray,
    ) : TransportFrame() {
        override fun equals(other: Any?): Boolean = this === other || (other is Binary && data.contentEquals(other.data))

        override fun hashCode(): Int = data.contentHashCode()

        override fun toString(): String = "TransportFrame.Binary(${data.size} bytes)"
    }

    /** Ping frame. */
    class Ping(
        val data: ByteArray,
    ) : TransportFrame() {
        override fun equals(other: Any?): Boolean = this === other || (other is Ping && data.contentEquals(other.data))

        override fun hashCode(): Int = data.contentHashCode()

        override fun toString(): String = "TransportFrame.Ping(${data.size} bytes)"
    }

    /** Pong frame (response to Ping). */
    class Pong(
        val data: ByteArray,
    ) : TransportFrame() {
        override fun equals(other: Any?): Boolean = this === other || (other is Pong && data.contentEquals(other.data))

        override fun hashCode(): Int = data.contentHashCode()

        override fun toString(): String = "TransportFrame.Pong(${data.size} bytes)"
    }

    /** Close frame. */
    data class Close(
        val code: Short,
        val reason: String,
    ) : TransportFrame()
}

/**
 * Library-owned WebSocket close reason.
 *
 * Used as parameter to [Transport.close] — conceptually distinct from
 * [TransportFrame.Close] which represents a received close frame.
 *
 * Internal visibility — not part of the public API.
 */
internal data class TransportCloseReason(
    val code: Short,
    val reason: String,
) {
    companion object {
        /** Normal closure (1000). */
        val NORMAL = TransportCloseReason(1000, "")

        /** Going away (1001) — used for reconnect and pong timeout. */
        val GOING_AWAY = TransportCloseReason(1001, "")
    }
}
