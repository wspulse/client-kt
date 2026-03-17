package com.wspulse.client

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackoffTest {
    @Test
    fun `jitter stays within half to full delay range`() {
        val base = 1.seconds
        val max = 30.seconds
        val attempt = 3 // delay = min(1s * 8, 30s) = 8s → range [4s, 8s]

        repeat(1000) {
            val result = backoff(attempt, base, max)
            assertTrue(result >= 4.seconds, "result $result should be >= 4s")
            assertTrue(result <= 8.seconds, "result $result should be <= 8s")
        }
    }

    @Test
    fun `delay is capped at max`() {
        val base = 1.seconds
        val max = 5.seconds
        val attempt = 20 // 2^20 * 1s = 1048576s >> 5s → capped at 5s → range [2.5s, 5s]

        repeat(1000) {
            val result = backoff(attempt, base, max)
            assertTrue(result >= 2500.milliseconds, "result $result should be >= 2.5s")
            assertTrue(result <= 5.seconds, "result $result should be <= 5s")
        }
    }

    @Test
    fun `attempt 63 does not overflow`() {
        val base = 1.seconds
        val max = 30.seconds

        // Should not throw and should be capped at max
        repeat(100) {
            val result = backoff(63, base, max)
            assertTrue(result >= 15.seconds, "result $result should be >= 15s")
            assertTrue(result <= 30.seconds, "result $result should be <= 30s")
        }
    }

    @Test
    fun `attempt 0 returns range around base`() {
        val base = 1.seconds
        val max = 30.seconds

        // delay = min(1s * 1, 30s) = 1s → range [0.5s, 1s]
        assertAll(
            (1..1000).map {
                {
                    val result = backoff(0, base, max)
                    assertTrue(result >= 500.milliseconds, "result $result should be >= 500ms")
                    assertTrue(result <= 1.seconds, "result $result should be <= 1s")
                }
            },
        )
    }
}
