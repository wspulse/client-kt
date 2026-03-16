package com.wspulse.client

/**
 * A WebSocket application-level frame.
 *
 * [payload] accepts only Kotlin stdlib types: [Map], [List], [String], [Number],
 * [Boolean], or `null`. The concrete types depend on the [Codec] used for
 * serialisation — [JsonCodec] enforces this contract.
 */
data class Frame(
    val id: String? = null,
    val event: String? = null,
    val payload: Any? = null,
)
