package io.github.slashncode.nothingeats.platform.kafka

import io.github.slashncode.nothingeats.platform.testing.KafkaTestCluster
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.selectAll

class OutboxRelayTest :
    FunSpec({
        val outbox = Outbox(source = "/nothingeats/identity")

        test("enqueued events are only stored when the transaction commits") {
            val database = messagingDatabase()

            shouldThrow<IllegalStateException> {
                database.transaction {
                    outbox.enqueue(
                        "t",
                        "u1",
                        "nothingeats.identity.user.registered.v1",
                        UserRegistered("u1", "de"),
                        UserRegistered.serializer(),
                    )
                    error("rollback")
                }
            }
            database.transaction {
                outbox.enqueue(
                    "t",
                    "u2",
                    "nothingeats.identity.user.registered.v1",
                    UserRegistered("u2", "en"),
                    UserRegistered.serializer(),
                )
            }

            database.transaction(readOnly = true) { OutboxEvents.selectAll().map { it[OutboxEvents.partitionKey] } } shouldContainExactly
                listOf("u2")
            database.stop()
        }

        test("the relay publishes CloudEvents in insertion order, keyed by aggregate, exactly once") {
            val database = messagingDatabase()
            val topic = KafkaTestCluster.topic("identity.user")
            database.transaction {
                listOf("u1" to "de", "u2" to "en", "u1" to "en").forEach { (user, locale) ->
                    outbox.enqueue(
                        topic,
                        user,
                        "nothingeats.identity.user.registered.v1",
                        UserRegistered(user, locale),
                        UserRegistered.serializer(),
                    )
                }
            }
            val relay = OutboxRelay(database, KafkaClients.producer(testKafka, "identity-test"))

            relay.publishBatch() shouldBe 3
            relay.publishBatch() shouldBe 0

            val records = readAll(topic, expected = 3)
            records.map { it.key() } shouldContainExactly listOf("u1", "u2", "u1")
            val events = records.map { EventJson.decodeFromString(CloudEvent.serializer(), it.value().decodeToString()) }
            events.map { it.decode(UserRegistered.serializer()).locale } shouldContainExactly listOf("de", "en", "en")
            events.first().source shouldBe "/nothingeats/identity"
            events.first().subject shouldBe "u1"
            records
                .first()
                .headers()
                .lastHeader("content-type")
                .value()
                .decodeToString() shouldBe CloudEvent.CONTENT_TYPE
            relay.stop()
            database.stop()
        }
    })
