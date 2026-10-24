package io.github.slashncode.nothingeats.platform.kafka

import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class OutboxRelayConfig(
    val pollInterval: Duration = 500.milliseconds,
    val batchSize: Int = 100,
    val maxBackoff: Duration = 30.seconds,
)

/**
 * Publishes outbox rows to Kafka in insertion order and marks them as published.
 *
 * Rows are locked with `FOR UPDATE SKIP LOCKED`, so several replicas can relay in parallel without
 * publishing the same row twice. If Kafka is down the transaction rolls back and the batch is retried
 * with exponential backoff; nothing is lost. Delivery is at-least-once, consumers deduplicate.
 */
class OutboxRelay(
    private val db: PostgresDatabase,
    private val producer: Producer<String, ByteArray>,
    private val config: OutboxRelayConfig = OutboxRelayConfig(),
    private val clock: Clock = Clock.systemUTC(),
) : LifecycleComponent {
    override val name = "outbox-relay"
    override val order = LifecycleOrder.TRAFFIC

    private val log = LoggerFactory.getLogger(OutboxRelay::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override suspend fun onReady() {
        job = scope.launch { relayLoop() }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
        producer.close(java.time.Duration.ofSeconds(SHUTDOWN_TIMEOUT_SECONDS))
    }

    /** Publishes one batch; returns how many events were published. */
    suspend fun publishBatch(): Int =
        db.transaction {
            val rows =
                OutboxEvents
                    .selectAll()
                    .where { OutboxEvents.publishedAt.isNull() }
                    .orderBy(OutboxEvents.position to SortOrder.ASC)
                    .limit(config.batchSize)
                    .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                    .toList()
            if (rows.isEmpty()) return@transaction 0

            rows
                .map { row ->
                    val record =
                        ProducerRecord(row[OutboxEvents.topic], row[OutboxEvents.partitionKey], row[OutboxEvents.payload].toByteArray())
                    record.headers().add("content-type", CloudEvent.CONTENT_TYPE.toByteArray())
                    producer.send(record)
                }.forEach { it.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS) }

            val now = clock.instant()
            OutboxEvents.update({ OutboxEvents.id inList rows.map { it[OutboxEvents.id] } }) { it[publishedAt] = now }
            rows.size
        }

    private suspend fun relayLoop() {
        var backoff = config.pollInterval
        while (scope.isActive) {
            try {
                val published = publishBatch()
                backoff = config.pollInterval
                if (published < config.batchSize) delay(config.pollInterval)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Publishing outbox events failed, retrying in {}", backoff, e)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(config.maxBackoff)
            }
        }
    }

    private companion object {
        const val SEND_TIMEOUT_SECONDS = 30L
        const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}
