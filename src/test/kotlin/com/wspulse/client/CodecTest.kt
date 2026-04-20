package com.wspulse.client

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodecTest {
    @Test
    fun `round-trip encode and decode`() {
        val message = Message(event = "chat", payload = mapOf("msg" to "hello"))
        val decoded = JsonCodec.decode(JsonCodec.encode(message))

        assertEquals("chat", decoded.event)
        assertEquals(mapOf("msg" to "hello"), decoded.payload)
    }

    @Test
    fun `null fields are omitted from JSON output`() {
        val message = Message() // all fields null
        val json = String(JsonCodec.encode(message), Charsets.UTF_8)

        assertEquals("{}", json)
    }

    @Test
    fun `unknown keys in JSON are ignored on decode`() {
        val json = """{"event":"e","payload":"v","extra":"ignored"}"""
        val message = JsonCodec.decode(json.toByteArray(Charsets.UTF_8))

        assertEquals("e", message.event)
        assertEquals("v", message.payload)
    }

    @Test
    fun `nested map payload round-trips correctly`() {
        val payload =
            mapOf(
                "user" to mapOf("name" to "alice", "age" to 30),
                "tags" to listOf("a", "b"),
            )
        val message = Message(payload = payload)
        val decoded = JsonCodec.decode(JsonCodec.encode(message))

        @Suppress("UNCHECKED_CAST")
        val decodedPayload = decoded.payload as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val user = decodedPayload["user"] as Map<String, Any?>
        assertEquals("alice", user["name"])
        assertEquals(30, user["age"])
        assertEquals(listOf("a", "b"), decodedPayload["tags"])
    }

    @Test
    fun `empty message decodes from empty JSON object`() {
        val message = JsonCodec.decode("{}".toByteArray(Charsets.UTF_8))

        assertNull(message.event)
        assertNull(message.payload)
    }

    @Test
    fun `string payload`() {
        val message = Message(payload = "hello")
        val decoded = JsonCodec.decode(JsonCodec.encode(message))
        assertEquals("hello", decoded.payload)
    }

    @Test
    fun `boolean payload`() {
        val message = Message(payload = true)
        val decoded = JsonCodec.decode(JsonCodec.encode(message))
        assertEquals(true, decoded.payload)
    }

    @Test
    fun `list payload`() {
        val message = Message(payload = listOf(1, "two", false))
        val decoded = JsonCodec.decode(JsonCodec.encode(message))
        assertEquals(listOf(1, "two", false), decoded.payload)
    }

    @Test
    fun `wireType is TEXT`() {
        assertEquals(WireType.TEXT, JsonCodec.wireType)
    }

    @Test
    fun `encode with unsupported payload type throws`() {
        val message = Message(payload = object {})
        assertThrows<IllegalArgumentException> {
            JsonCodec.encode(message)
        }
    }

    @Test
    fun `decode with malformed JSON throws`() {
        assertThrows<org.json.JSONException> {
            JsonCodec.decode("not valid json".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `null value in map payload round-trips`() {
        val message = Message(payload = mapOf("key" to null))
        val decoded = JsonCodec.decode(JsonCodec.encode(message))

        @Suppress("UNCHECKED_CAST")
        val payload = decoded.payload as Map<String, Any?>
        assertNull(payload["key"])
    }

    @Test
    fun `null value in list payload round-trips`() {
        val message = Message(payload = listOf("a", null, "b"))
        val decoded = JsonCodec.decode(JsonCodec.encode(message))
        assertEquals(listOf("a", null, "b"), decoded.payload)
    }
}
