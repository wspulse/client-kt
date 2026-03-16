package com.wspulse.client

import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Computes an exponential backoff delay with equal jitter.
 *
 * The result is uniformly distributed in `[delay * 0.5, delay * 1.0]` where
 * `delay = min(base * 2^attempt, max)`. The shift is capped at 62 bits to
 * prevent overflow.
 *
 * This function must produce the same distribution as all other
 * `wspulse/client-*` libraries.
 *
 * @param attempt 0-based reconnection attempt number.
 * @param base    base delay duration.
 * @param max     upper bound for the computed delay.
 * @return jittered backoff duration.
 */
internal fun backoff(attempt: Int, base: Duration, max: Duration): Duration {
    val shift = min(attempt, 62)
    val baseNs = base.inWholeNanoseconds
    val maxNs = max.inWholeNanoseconds
    val multiplier = 1L shl shift
    // Detect overflow: if base > max / multiplier, the product would exceed max (or overflow).
    val delayNs = if (multiplier != 0L && baseNs > maxNs / multiplier) {
        maxNs
    } else {
        min(baseNs * multiplier, maxNs)
    }
    val halfNs = delayNs / 2
    val jitterNs = if (halfNs > 0) Random.nextLong(halfNs + 1) else 0L
    return (halfNs + jitterNs).nanoseconds
}
