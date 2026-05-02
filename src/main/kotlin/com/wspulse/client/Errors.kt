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

/** Thrown when the send buffer is full and the message cannot be enqueued. */
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
 * Delivered to [ClientConfig.onTransportDrop] as the cause when a concrete
 * WebSocket close frame is received, including when the frame carries no status
 * body ([StatusCode.NO_STATUS_RECEIVED], 1005).
 *
 * Only [StatusCode.ABNORMAL_CLOSURE] (1006) is excluded — that code indicates
 * a TCP drop without any close handshake, and is surfaced as
 * [ConnectionLostException] instead.
 */
class ServerClosedException(
    val code: StatusCode,
    val reason: String,
) : WspulseException(
        // The `reason` is interpolated without escaping. This is intentional:
        // the exception message is for human-readable logging only, and callers
        // that need structured access read the typed `code`/`reason` fields
        // directly. Pulling in org.json.JSONObject.quote (or equivalent) to
        // escape quotes/control characters in the message is not worth the
        // dependency cost for a diagnostic-only string.
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
