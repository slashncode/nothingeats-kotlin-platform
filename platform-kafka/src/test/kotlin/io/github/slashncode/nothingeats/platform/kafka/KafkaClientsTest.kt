package io.github.slashncode.nothingeats.platform.kafka

import com.sksamuel.hoplite.Secret
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.config.types.Password
import org.apache.kafka.common.security.JaasContext
import java.util.concurrent.ExecutionException
import kotlin.time.Duration.Companion.milliseconds

class KafkaClientsTest :
    FunSpec({
        test("without SASL a client only needs the address; with it, a SCRAM login Kafka can parse") {
            KafkaConfig("kafka:9092").connection() shouldBe mapOf("bootstrap.servers" to "kafka:9092")

            val config = KafkaConfig("localhost:19094", KafkaSasl("developer", Secret("pa\"ss\\word")))
            val connection = config.connection()

            connection["security.protocol"] shouldBe "SASL_PLAINTEXT"
            connection["sasl.mechanism"] shouldBe "SCRAM-SHA-512"
            // Kafka parses the JAAS line when a client starts; a broken quote would throw here.
            Admin.create(connection + ("request.timeout.ms" to 100)).close()
            val options = JaasContext.loadClientContext(mapOf("sasl.jaas.config" to Password(connection["sasl.jaas.config"] as String)))
            options.configurationEntries().single().options shouldBe mapOf("username" to "developer", "password" to "pa\"ss\\word")
        }

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
