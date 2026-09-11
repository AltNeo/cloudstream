package com.lagradost.cloudstream3.companion.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object ProtocolJson {
    val format: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeEnvelope(envelope: Envelope): ByteArray =
        format.encodeToString(Envelope.serializer(), envelope).encodeToByteArray()

    fun decodeEnvelope(bytes: ByteArray): Envelope =
        format.decodeFromString(Envelope.serializer(), bytes.decodeToString())

    fun <T> encode(strategy: SerializationStrategy<T>, value: T): JsonElement =
        format.encodeToJsonElement(strategy, value)

    fun <T> decode(strategy: DeserializationStrategy<T>, value: JsonElement): T =
        format.decodeFromJsonElement(strategy, value)
}

