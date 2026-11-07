package io.github.slashncode.nothingeats.platform.kafka

import io.github.slashncode.nothingeats.platform.testing.KafkaTestCluster
import io.github.slashncode.nothingeats.platform.testing.RecordingAnalytics
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes

class ConsumerLagTest :
    FunSpec({
        val sent = Instant.parse("2026-11-07T10:00:00Z")

        /** Three events a minute apart on a fresh topic; the group has handled the first one. */
        fun groupBehind(): Pair<String, String> {
            val topic = KafkaTestCluster.topic("lag")
            val group = "group-${System.nanoTime()}"
            KafkaClients.producer(testKafka, "lag-test").use { producer ->
                (0..2).forEach { minute ->
                    producer.send(ProducerRecord(topic, 0, sent.plusSeconds(60L * minute).toEpochMilli(), "k", ByteArray(0))).get()
                }
            }
            KafkaClients.consumer(testKafka, group).use { consumer ->
                val partition = TopicPartition(topic, 0)
                consumer.assign(listOf(partition))
                consumer.commitSync(mapOf(partition to OffsetAndMetadata(1)))
            }
            return topic to group
        }

        fun clockAt(instant: Instant) = Clock.fixed(instant, ZoneOffset.UTC)

        test("a group behind waits as long as its oldest unhandled event") {
            val (topic, group) = groupBehind()

            val lag = ConsumerLag(testKafka, clockAt(sent.plusSeconds(20 * 60))).of(group)

            // The oldest unhandled event is the second one, sent a minute after the first.
            lag shouldContainExactly listOf(PartitionLag(topic, 0, events = 2, waited = 19.minutes))
        }

        test("a group that has handled everything, or never committed, is not behind") {
            val (topic, group) = groupBehind()
            KafkaClients.consumer(testKafka, group).use { consumer ->
                val partition = TopicPartition(topic, 0)
                consumer.assign(listOf(partition))
                consumer.commitSync(mapOf(partition to OffsetAndMetadata(3)))
            }

            ConsumerLag(testKafka).of(group).shouldBeEmpty()
            ConsumerLag(testKafka).of("never-seen-${System.nanoTime()}").shouldBeEmpty()
        }

        test("the watch reports a group whose events waited too long, and stays quiet otherwise") {
            val (_, group) = groupBehind()
            val analytics = RecordingAnalytics()

            ConsumerLagWatch(group, ConsumerLag(testKafka, clockAt(sent.plusSeconds(5 * 60))), analytics, maxWait = 10.minutes)
                .check()
                .shouldBeEmpty()
            analytics.exceptions.shouldBeEmpty()

            val stalled =
                ConsumerLagWatch(
                    group,
                    ConsumerLag(testKafka, clockAt(sent.plusSeconds(60 * 60))),
                    analytics,
                    maxWait = 10.minutes,
                ).check()

            stalled.single().events shouldBe 2
            analytics.exceptions
                .single()
                .shouldBeInstanceOf<ConsumerStalledException>()
                .groupId shouldBe group
        }
    })
