package io.github.slashncode.nothingeats.platform.kafka

import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.MigrationSet
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.testing.KafkaTestCluster
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import kotlinx.serialization.Serializable
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import java.time.Duration

@Serializable
data class UserRegistered(
    val userId: String,
    val locale: String,
)

suspend fun messagingDatabase(): PostgresDatabase {
    val config = PostgresTestDatabases.create()
    val migrations = FlywayMigrations(config, listOf(KafkaMigrations, MigrationSet.SERVICE))
    migrations.migrate()
    return PostgresDatabase(config, migrations).also { it.start() }
}

val testKafka get() = KafkaConfig(KafkaTestCluster.bootstrapServers)

/** Reads everything currently on [topic] with a throwaway consumer group. */
fun readAll(
    topic: String,
    expected: Int,
): List<ConsumerRecord<String, ByteArray>> {
    KafkaClients.consumer(testKafka, "reader-${System.nanoTime()}").use { consumer: KafkaConsumer<String, ByteArray> ->
        consumer.subscribe(listOf(topic))
        val records = mutableListOf<ConsumerRecord<String, ByteArray>>()
        val deadline = System.currentTimeMillis() + 15_000
        while (records.size < expected && System.currentTimeMillis() < deadline) {
            records += consumer.poll(Duration.ofMillis(250))
        }
        return records
    }
}
