package io.github.slashncode.nothingeats.platform.kafka

import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.jdbc.insert
import java.time.Clock
import kotlin.uuid.Uuid

/**
 * Writes events to the outbox table. Must be called inside a database transaction — the one that
 * also changes the state the event describes — so either both are committed or neither is.
 */
class Outbox(
    /** CloudEvents `source`, e.g. `/nothingeats/identity`. */
    private val source: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun <T> enqueue(
        topic: String,
        key: String,
        type: String,
        data: T,
        serializer: KSerializer<T>,
    ): CloudEvent {
        val now = clock.instant()
        val event =
            CloudEvent(
                id = Uuid.random().toString(),
                source = source,
                type = type,
                subject = key,
                time = now.toString(),
                data = EventJson.encodeToJsonElement(serializer, data),
            )
        OutboxEvents.insert {
            it[id] = Uuid.parse(event.id)
            it[OutboxEvents.topic] = topic
            it[partitionKey] = key
            it[OutboxEvents.type] = type
            it[payload] = EventJson.encodeToString(CloudEvent.serializer(), event)
            it[createdAt] = now
        }
        return event
    }
}
