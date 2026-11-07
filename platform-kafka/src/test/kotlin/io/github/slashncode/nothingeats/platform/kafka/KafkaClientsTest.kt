package io.github.slashncode.nothingeats.platform.kafka

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import org.apache.kafka.clients.producer.ProducerRecord
import java.util.concurrent.ExecutionException
import kotlin.time.Duration.Companion.milliseconds

class KafkaClientsTest :
    FunSpec({
        test("a producer with a short maxBlock gives up quickly when no broker answers") {
            // Nothing listens on port 1: the producer never gets metadata for the topic.
            KafkaClients.producer(KafkaConfig("127.0.0.1:1"), "unreachable", maxBlock = 300.milliseconds).use { producer ->
                val started = System.nanoTime()
                val sent = producer.send(ProducerRecord("positions", "k", ByteArray(0)))
                val blockedMillis = (System.nanoTime() - started) / 1_000_000

                blockedMillis shouldBeLessThan 2_000
                shouldThrow<ExecutionException> { sent.get() }
            }
        }
    })
