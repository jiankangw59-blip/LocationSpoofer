package com.vincenthzr.locationspoofer.data.db

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A legacy backup may omit the observation time; only a live capture should use the current clock. */
internal object BackupLocationRecordSerializer : KSerializer<LocationRecord> {
    private val record = LocationRecord.serializer()
    override val descriptor get() = record.descriptor

    override fun serialize(encoder: Encoder, value: LocationRecord) = record.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): LocationRecord {
        if (decoder !is JsonDecoder) return record.deserialize(decoder)
        // Inspect just the small embedded record, not the entire backup or nearby-device arrays.
        val input = decoder.decodeJsonElement()
        val normalized = if (input is JsonObject && (input["timestamp"] ?: JsonNull) == JsonNull) {
            JsonObject(input + ("timestamp" to JsonPrimitive(0L)))
        } else input
        return decoder.json.decodeFromJsonElement(record, normalized)
    }
}
