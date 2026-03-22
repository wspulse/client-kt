package com.wspulse.client

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorsTest {
    @Test
    fun `ConnectionClosedException has wspulse prefix`() {
        val ex = ConnectionClosedException()
        assertTrue(ex.message!!.startsWith("wspulse:"))
    }

    @Test
    fun `SendBufferFullException has wspulse prefix`() {
        val ex = SendBufferFullException()
        assertTrue(ex.message!!.startsWith("wspulse:"))
    }

    @Test
    fun `RetriesExhaustedException includes attempt count`() {
        val ex = RetriesExhaustedException(5)
        assertTrue(ex.message!!.contains("5"))
        assertTrue(ex.message!!.startsWith("wspulse:"))
    }

    @Test
    fun `ConnectionLostException has wspulse prefix`() {
        val ex = ConnectionLostException()
        assertTrue(ex.message!!.startsWith("wspulse:"))
    }

    @Test
    fun `ConnectionLostException chains cause`() {
        val cause = RuntimeException("socket closed")
        val ex = ConnectionLostException(cause)
        assertEquals(cause, ex.cause)
    }

    @Test
    fun `ConnectionLostException without cause has null cause`() {
        val ex = ConnectionLostException()
        assertNull(ex.cause)
    }

    @Test
    fun `all exceptions extend WspulseException`() {
        assertIs<WspulseException>(ConnectionClosedException())
        assertIs<WspulseException>(SendBufferFullException())
        assertIs<WspulseException>(RetriesExhaustedException(1))
        assertIs<WspulseException>(ConnectionLostException())
    }
}
