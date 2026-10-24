package io.github.slashncode.nothingeats.platform.testing

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.testcontainers.kafka.KafkaContainer
import java.util.UUID

/**
 * One Kafka broker per test JVM (same version as the local cluster), fresh topics per test:
 * [topic] returns a unique name and creates it, so tests never see each other's records.
 */
object KafkaTestCluster {
    private const val IMAGE = "apache/kafka:4.2.1"

    private val container: KafkaContainer by lazy { KafkaContainer(IMAGE).apply { start() } }

    val bootstrapServers: String get() = container.bootstrapServers

    fun topic(prefix: String = "test"): String = "$prefix-${UUID.randomUUID()}".also { createTopics(it) }

    /** Creates the topics; existing ones are left alone, so fixed topic names can be created by every test. */
    fun createTopics(vararg names: String) {
        AdminClient.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers)).use { admin ->
            val existing = admin.listTopics().names().get()
            val missing = names.filterNot { it in existing }
            if (missing.isNotEmpty()) admin.createTopics(missing.map { NewTopic(it, 1, 1.toShort()) }).all().get()
        }
    }
}
