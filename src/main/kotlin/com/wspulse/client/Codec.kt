package com.wspulse.client

import org.json.JSONArray
import org.json.JSONObject

/** Wire format used by a [Codec] implementation. */
enum class WireType { TEXT, BINARY }

/**
 * Encodes and decodes [Message] instances for WebSocket transport.
 *
 * Implementations must be stateless and safe for concurrent use.
 */
interface Codec {
    fun encode(message: Message): ByteArray

    fun decode(data: ByteArray): Message

    val wireType: WireType
}

/**
 * Default JSON codec using `org.json`.
 *
 * Encodes [Message] fields as a JSON object with keys `"event"` and `"payload"`.
 * Null fields are omitted from the output. On decode, unknown keys are
 * silently ignored.
 *
 * Payload values are recursively converted between `org.json` types and Kotlin
 * stdlib types ([Map], [List], [String], [Number], [Boolean], `null`).
 */
object JsonCodec : Codec {
    override val wireType: WireType = WireType.TEXT

    override fun encode(message: Message): ByteArray {
        val obj = JSONObject()
        message.event?.let { obj.put("event", it) }
        message.payload?.let { obj.put("payload", toJson(it)) }
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    override fun decode(data: ByteArray): Message {
        val obj = JSONObject(String(data, Charsets.UTF_8))
        return Message(
            event = obj.opt("event") as? String,
            payload = obj.opt("payload")?.let { fromJson(it) },
        )
    }

    // -- internal conversion helpers ------------------------------------------

    private fun toJson(value: Any): Any =
        when (value) {
            is Map<*, *> ->
                JSONObject().also { obj ->
                    value.forEach { (k, v) ->
                        require(k is String) { "wspulse: map key must be String, got ${k?.let { it::class.qualifiedName } ?: "null"}" }
                        obj.put(k, v?.let { toJson(it) } ?: JSONObject.NULL)
                    }
                }
            is List<*> ->
                JSONArray().also { arr ->
                    value.forEach { v -> arr.put(v?.let { toJson(it) } ?: JSONObject.NULL) }
                }
            is String, is Number, is Boolean -> value
            else -> throw IllegalArgumentException(
                "wspulse: unsupported payload type: ${value::class.qualifiedName}",
            )
        }

    private fun fromJson(value: Any): Any? =
        when (value) {
            JSONObject.NULL -> null
            is JSONObject ->
                buildMap {
                    value.keys().forEach { key -> put(key, fromJson(value.get(key))) }
                }
            is JSONArray ->
                buildList {
                    for (i in 0 until value.length()) {
                        add(fromJson(value.get(i)))
                    }
                }
            is String, is Number, is Boolean -> value
            else -> value
        }
}
