package com.wspulse.client

/**
 * Base class for all wspulse client exceptions.
 *
 * Subclasses represent specific failure modes. All are unchecked
 * ([RuntimeException]) for ergonomic JVM interop.
 */
sealed class WspulseException(
    message: String,
) : RuntimeException(message)

/** Thrown when [Client.send] is called after the client has been closed. */
class ConnectionClosedException : WspulseException("wspulse: connection is closed")

/** Thrown when the send buffer is full and the frame cannot be enqueued. */
class SendBufferFullException : WspulseException("wspulse: send buffer is full")

/** Thrown when the maximum number of reconnection attempts has been exhausted. */
class RetriesExhaustedException(
    attempts: Int,
) : WspulseException("wspulse: retries exhausted after $attempts attempts")

/** Thrown when the underlying transport connection is lost unexpectedly. */
class ConnectionLostException(
    cause: Throwable? = null,
) : WspulseException("wspulse: connection lost") {
    init {
        if (cause != null) initCause(cause)
    }
}

/**
 * Thrown when the server sends a WebSocket close frame.
 *
 * Carries the close code and reason read directly from the frame so callers can
 * distinguish disconnect causes (e.g. `GOING_AWAY` vs `POLICY_VIOLATION`).
 *
 * Delivered to [ClientConfig.onTransportDrop] as the cause. Pseudo-codes
 * [StatusCode.NO_STATUS_RECEIVED] (1005) and [StatusCode.ABNORMAL_CLOSURE] (1006)
 * are NOT reported through this exception — they surface as a generic
 * [ConnectionLostException].
 */
class ServerClosedException(
    val code: StatusCode,
    val reason: String,
) : WspulseException(
        buildString {
            append("wspulse: server closed connection: code=")
            append(code.value)
            if (reason.isNotEmpty()) {
                append(", reason=\"")
                append(reason)
                append('"')
            }
        },
    )
