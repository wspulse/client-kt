package com.wspulse.client

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodecTest {

    @Test
    fun `round-trip encode and decode`() {
        val frame = Frame(id = "abc", event = "chat", payload = mapOf("msg" to "hello"))
        val decoded = JsonCodec.decode(JsonCodec.encode(frame))

        assertEquals("abc", decoded.id)
        assertEquals("chat", decoded.event)
        assertEquals(mapOf("msg" to "hello"), decoded.payload)
    }

    @Test
    fun `null fields are omitted from JSON output`() {
        val frame = Frame() // all fields null
        val json = String(JsonCodec.encode(frame), Charsets.UTF_8)

        assertEquals("{}", json)
    }

    @Test
    fun `unknown keys in JSON are ignored on decode`() {
        val json = """{"id":"1","event":"e","payload":"v","extra":"ignored"}"""
        val frame = JsonCodec.decode(json.toByteArray(Charsets.UTF_8))

        assertEquals("1", frame.id)
        assertEquals("e", frame.event)
        assertEquals("v", frame.payload)
    }

    @Test
    fun `nested map payload round-trips correctly`() {
        val payload = mapOf(
            "user" to mapOf("name" to "alice", "age" to 30),
            "tags" to listOf("a", "b"),
        )
        val frame = Frame(payload = payload)
        val decoded = JsonCodec.decode(JsonCodec.encode(frame))

        @Suppress("UNCHECKED_CAST")
        val decodedPayload = decoded.payload as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val user = decodedPayload["user"] as Map<String, Any?>
        assertEquals("alice", user["name"])
        assertEquals(30, user["age"])
        assertEquals(listOf("a", "b"), decodedPayload["tags"])
    }

    @Test
    fun `empty frame decodes from empty JSON object`() {
        val frame = JsonCodec.decode("{}".toByteArray(Charsets.UTF_8))

        assertNull(frame.id)
        assertNull(frame.event)
        assertNull(frame.payload)
    }

    @Test
    fun `string payload`() {
        val frame = Frame(payload = "hello")
        val decoded = JsonCodec.decode(JsonCodec.encode(frame))
        assertEquals("hello", decoded.payload)
    }

    @Test
    fun `boolean payload`() {
        val frame = Frame(payload = true)
        val decoded = JsonCodec.decode(JsonCodec.encode(frame))
        assertEquals(true, decoded.payload)
    }

    @Test
    fun `list payload`() {
        val frame = Frame(payload = listOf(1, "two", false))
        val decoded = JsonCodec.decode(JsonCodec.encode(frame))
        assertEquals(listOf(1, "two", false), decoded.payload)
    }

    @Test
    fun `frameType is TEXT`() {
        assertEquals(FrameType.TEXT, JsonCodec.frameType)
    }
}
