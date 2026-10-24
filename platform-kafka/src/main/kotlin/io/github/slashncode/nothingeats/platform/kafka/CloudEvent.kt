package io.github.slashncode.nothingeats.platform.kafka

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** JSON settings for events: tolerant readers, so producers can add fields without breaking consumers. */
val EventJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

/**
 * Every Kafka message is a CloudEvent in structured mode (the whole envelope is the record value).
 *
 * - [type]: `nothingeats.<context>.<aggregate>.<event>.v<n>`, e.g. `nothingeats.identity.user.registered.v1`
 * - [subject]: the aggregate id, also used as the record key (ordering per aggregate)
 */
@Serializable
data class CloudEvent(
    val specversion: String = "1.0",
    val id: String,
    val source: String,
    val type: String,
    val subject: String? = null,
    val time: String,
    val datacontenttype: String = "application/json",
    val data: JsonElement,
) {
    fun <T> decode(serializer: KSerializer<T>): T = EventJson.decodeFromJsonElement(serializer, data)

    companion object {
        const val CONTENT_TYPE = "application/cloudevents+json"
    }
}
