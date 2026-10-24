package io.github.slashncode.nothingeats.platform.kafka

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer

data class KafkaConfig(
    /** Defaults to the local k3d cluster as seen from the host. */
    val bootstrapServers: String = "localhost:19094",
)

object KafkaClients {
    /** Idempotent producer with `acks=all`: no duplicates from retries, ordering kept per partition. */
    fun producer(
        config: KafkaConfig,
        clientId: String,
    ): KafkaProducer<String, ByteArray> =
        KafkaProducer(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to config.bootstrapServers,
                ProducerConfig.CLIENT_ID_CONFIG to clientId,
                ProducerConfig.ACKS_CONFIG to "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
                ProducerConfig.LINGER_MS_CONFIG to 5,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to 15_000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to 30_000,
            ),
            StringSerializer(),
            ByteArraySerializer(),
        )

    /** Manual commits only: offsets move after an event was handled or parked in the DLQ. */
    fun consumer(
        config: KafkaConfig,
        groupId: String,
    ): KafkaConsumer<String, ByteArray> =
        KafkaConsumer(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to config.bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to groupId,
                ConsumerConfig.CLIENT_ID_CONFIG to groupId,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.ISOLATION_LEVEL_CONFIG to "read_committed",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 50,
            ),
            StringDeserializer(),
            ByteArrayDeserializer(),
        )
}
