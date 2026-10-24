package io.github.slashncode.nothingeats.platform.kafka

import io.github.slashncode.nothingeats.platform.persistence.MigrationSet
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/** Add to the service's migration sets, before [MigrationSet.SERVICE]. */
val KafkaMigrations = MigrationSet("platform-kafka", listOf("classpath:db/platform/kafka"), "flyway_history_platform_kafka")

internal object OutboxEvents : Table("outbox_events") {
    val id = uuid("id")
    val position = long("position").autoIncrement()
    val topic = text("topic")
    val partitionKey = text("partition_key")
    val type = text("type")
    val payload = text("payload")
    val createdAt = timestamp("created_at")
    val publishedAt = timestamp("published_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object InboxEvents : Table("inbox_events") {
    val consumer = text("consumer")
    val eventId = text("event_id")
    val processedAt = timestamp("processed_at")

    override val primaryKey = PrimaryKey(consumer, eventId)
}
