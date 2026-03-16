package com.wspulse.client

import org.json.JSONArray
import org.json.JSONObject

/** Wire format used by a [Codec] implementation. */
enum class FrameType { TEXT, BINARY }

/**
 * Encodes and decodes [Frame] instances for WebSocket transport.
 *
 * Implementations must be stateless and safe for concurrent use.
 */
interface Codec {
    fun encode(frame: Frame): ByteArray

    fun decode(data: ByteArray): Frame

    val frameType: FrameType
}

/**
 * Default JSON codec using `org.json`.
 *
 * Encodes [Frame] fields as a JSON object with keys `"id"`, `"event"`, and
 * `"payload"`. Null fields are omitted from the output. On decode, unknown
 * keys are silently ignored.
 *
 * Payload values are recursively converted between `org.json` types and Kotlin
 * stdlib types ([Map], [List], [String], [Number], [Boolean], `null`).
 */
object JsonCodec : Codec {
    override val frameType: FrameType = FrameType.TEXT

    override fun encode(frame: Frame): ByteArray {
        val obj = JSONObject()
        frame.id?.let { obj.put("id", it) }
        frame.event?.let { obj.put("event", it) }
        frame.payload?.let { obj.put("payload", toJson(it)) }
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    override fun decode(data: ByteArray): Frame {
        val obj = JSONObject(String(data, Charsets.UTF_8))
        return Frame(
            id = obj.optString("id", null),
            event = obj.optString("event", null),
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
