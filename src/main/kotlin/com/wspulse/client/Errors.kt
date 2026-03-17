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
