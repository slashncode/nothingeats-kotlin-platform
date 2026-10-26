package io.github.slashncode.nothingeats.platform.kafka

import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthStatus
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.testing.KafkaTestCluster
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.delay
import org.apache.kafka.clients.producer.ProducerRecord
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class RecordingHandler(
    override val topic: String,
    private val failWith: Exception? = null,
) : EventHandler {
    val handled = CopyOnWriteArrayList<String>()
    var attempts = 0

    override suspend fun handle(event: CloudEvent) {
        attempts++
        failWith?.let { throw it }
        handled += event.decode(UserRegistered.serializer()).userId
    }
}

private fun event(
    id: String,
    userId: String,
) = CloudEvent(
    id = id,
    source = "/nothingeats/identity",
    type = "nothingeats.identity.user.registered.v1",
    subject = userId,
    time = "2026-10-24T12:00:00Z",
    data = EventJson.encodeToJsonElement(UserRegistered.serializer(), UserRegistered(userId, "de")),
)

private fun send(
    topic: String,
    key: String,
    value: String,
) {
    KafkaClients.producer(testKafka, "test-producer").use { it.send(ProducerRecord(topic, key, value.toByteArray())).get() }
}

private fun consumer(
    groupId: String,
    handler: EventHandler,
    database: PostgresDatabase?,
) = EventConsumer(
    config = EventConsumerConfig(groupId = groupId, retryBackoff = 50.milliseconds, pollTimeout = 200.milliseconds),
    handlers = setOf(handler),
    db = database,
    consumerFactory = { KafkaClients.consumer(testKafka, groupId) },
    deadLetters = KafkaClients.producer(testKafka, "$groupId-dlq"),
)

class EventConsumerTest :
    FunSpec({
        test("redelivered events are handled once thanks to the inbox") {
            val database = messagingDatabase()
            val topic = KafkaTestCluster.topic("identity.user")
            val handler = RecordingHandler(topic)
            val json = EventJson.encodeToString(CloudEvent.serializer(), event("e1", "u1"))
            send(topic, "u1", json)
            send(topic, "u1", json)
            send(topic, "u2", EventJson.encodeToString(CloudEvent.serializer(), event("e2", "u2")))
            val consumer = consumer("notification-${System.nanoTime()}", handler, database)

            consumer.onReady()

            eventually(15.seconds) { handler.handled shouldContainExactly listOf("u1", "u2") }
            delay(500.milliseconds)
            handler.handled shouldContainExactly listOf("u1", "u2")
            consumer.check() shouldBe HealthStatus.UP
            consumer.stop()
            database.stop()
        }

        test("failing events are retried, then parked in the dead-letter topic") {
            val database = messagingDatabase()
            val topic = KafkaTestCluster.topic("identity.user")
            val group = "notification-${System.nanoTime()}"
            KafkaTestCluster.createTopics("$group.dlq")
            val handler = RecordingHandler(topic, failWith = IllegalStateException("SMTP down"))
            send(topic, "u1", EventJson.encodeToString(CloudEvent.serializer(), event("e1", "u1")))
            send(topic, "u2", "{not a cloud event")
            val consumer = consumer(group, handler, database)

            consumer.onReady()

            val dead = readAll("$group.dlq", expected = 2)
            dead.map { it.key() } shouldContainExactly listOf("u1", "u2")
            dead
                .first()
                .headers()
                .lastHeader("dlq-error")
                .value()
                .decodeToString() shouldContain "SMTP down"
            dead
                .first()
                .headers()
                .lastHeader("dlq-original-topic")
                .value()
                .decodeToString() shouldBe topic
            handler.attempts shouldBe 3
            consumer.stop()
            database.stop()
        }

        test("without an inbox, positioned handlers get every delivery with its offset") {
            val topic = KafkaTestCluster.topic("catalog.dish")
            val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, Long>>()
            val handler =
                object : PositionedEventHandler {
                    override val topic = topic

                    override suspend fun handleAt(
                        event: CloudEvent,
                        position: EventPosition,
                    ) {
                        seen += event.subject to position.offset
                    }
                }
            val json = EventJson.encodeToString(CloudEvent.serializer(), event("e1", "u1"))
            send(topic, "u1", json)
            send(topic, "u1", json)
            val consumer = consumer("search-${System.nanoTime()}", handler, database = null)

            consumer.onReady()

            eventually(15.seconds) { seen shouldContainExactly listOf("u1" to 0L, "u1" to 1L) }
            consumer.stop()
        }
    })
