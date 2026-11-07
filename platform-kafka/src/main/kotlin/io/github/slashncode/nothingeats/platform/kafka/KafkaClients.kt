package io.github.slashncode.nothingeats.platform.kafka

import com.sksamuel.hoplite.Secret
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class KafkaConfig(
    /** Defaults to the local k3d cluster as seen from the host. */
    val bootstrapServers: String = "localhost:19094",
    /**
     * Only for the cluster's listener for the host, which wants a SCRAM login
     * (`KAFKA__SASL__USERNAME`, `KAFKA__SASL__PASSWORD`); the listener for the cluster's own pods
     * has none, its network policy admits only the shop's pods.
     */
    val sasl: KafkaSasl? = null,
) {
    /** Where the broker is and how to sign in: the settings every client starts from. */
    fun connection(): Map<String, Any> =
        buildMap {
            put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            if (sasl != null) {
                put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT")
                put(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-512")
                put(
                    SaslConfigs.SASL_JAAS_CONFIG,
                    "org.apache.kafka.common.security.scram.ScramLoginModule required " +
                        "username=\"${jaasQuoted(sasl.username)}\" password=\"${jaasQuoted(sasl.password.value)}\";",
                )
            }
        }

    private fun jaasQuoted(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
}

data class KafkaSasl(
    val username: String,
    val password: Secret,
)

object KafkaClients {
    /**
     * Idempotent producer with `acks=all`: no duplicates from retries, ordering kept per partition.
     * [maxBlock] bounds how long `send` itself may block while the broker is unknown (Kafka's
     * default is a minute); fire-and-forget senders keep it short so an outage cannot stall them.
     */
    fun producer(
        config: KafkaConfig,
        clientId: String,
        maxBlock: Duration = 60.seconds,
    ): KafkaProducer<String, ByteArray> =
        KafkaProducer(
            config.connection() +
                mapOf(
                    ProducerConfig.CLIENT_ID_CONFIG to clientId,
                    ProducerConfig.ACKS_CONFIG to "all",
                    ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
                    ProducerConfig.LINGER_MS_CONFIG to 5,
                    ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to 15_000,
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to 30_000,
                    ProducerConfig.MAX_BLOCK_MS_CONFIG to maxBlock.inWholeMilliseconds,
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
            config.connection() +
                mapOf(
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
