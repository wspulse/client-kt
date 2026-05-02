package com.wspulse.client

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StatusCodeTest {
    @Test
    fun `StatusCode rejects negative values`() {
        assertFailsWith<IllegalArgumentException> {
            StatusCode(-1)
        }
    }

    @Test
    fun `StatusCode rejects values above 65535`() {
        assertFailsWith<IllegalArgumentException> {
            StatusCode(65536)
        }
    }

    @Test
    fun `StatusCode accepts lower boundary 0`() {
        assertEquals(0, StatusCode(0).value)
    }

    @Test
    fun `StatusCode accepts upper boundary 65535`() {
        assertEquals(65535, StatusCode(65535).value)
    }

    @Test
    fun `StatusCode accepts private-use range value`() {
        assertEquals(4000, StatusCode(4000).value)
    }
}
