package io.github.slashncode.nothingeats.platform.kafka

import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** A partition with [events] after the group's committed offset, the oldest waiting for [waited]. */
data class PartitionLag(
    val topic: String,
    val partition: Int,
    val events: Long,
    val waited: Duration,
)

/**
 * How far a consumer group is behind, in time: for every partition it has committed on and that
 * holds events after the committed offset, the age of the oldest of them. Reads only what Kafka
 * keeps anyway (committed offsets, end offsets, record timestamps), so it needs no state.
 * Partitions the group never committed on (a new service still starting) are left out.
 */
class ConsumerLag(
    private val config: KafkaConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun of(groupId: String): List<PartitionLag> =
        Admin.create(config.connection()).use { admin ->
            val committed =
                admin
                    .listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .filterValues { it != null }
                    .mapValues { it.value.offset() }
            if (committed.isEmpty()) return emptyList()
            val ends =
                admin
                    .listOffsets(committed.keys.associateWith { OffsetSpec.latest() })
                    .all()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .mapValues { it.value.offset() }
            val behind = committed.filter { (partition, offset) -> (ends[partition] ?: offset) > offset }
            if (behind.isEmpty()) return emptyList()
            reader().use { reader ->
                behind.map { (partition, offset) ->
                    PartitionLag(
                        partition.topic(),
                        partition.partition(),
                        ends.getValue(partition) - offset,
                        oldestWait(reader, partition, offset),
                    )
                }
            }
        }

    /** The age of the first record at or after [offset]; zero when there is none (transaction markers only). */
    private fun oldestWait(
        reader: KafkaConsumer<ByteArray, ByteArray>,
        partition: TopicPartition,
        offset: Long,
    ): Duration {
        reader.assign(listOf(partition))
        reader.seek(partition, offset)
        val record = reader.poll(java.time.Duration.ofSeconds(TIMEOUT_SECONDS)).records(partition).firstOrNull() ?: return Duration.ZERO
        return (clock.millis() - record.timestamp()).coerceAtLeast(0).milliseconds
    }

    // No group: it only looks, it never commits.
    private fun reader() =
        KafkaConsumer(
            config.connection() +
                mapOf(
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                    ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 1,
                    ConsumerConfig.ISOLATION_LEVEL_CONFIG to "read_committed",
                ),
            ByteArrayDeserializer(),
            ByteArrayDeserializer(),
        )

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}

/** A consumer group whose oldest unprocessed events have waited longer than allowed. */
class ConsumerStalledException(
    val groupId: String,
    val partitions: List<PartitionLag>,
) : RuntimeException(
        "Consumer group $groupId is not keeping up: " +
            partitions.joinToString {
                "${it.topic}-${it.partition} has ${it.events} events, the oldest waiting ${it.waited.inWholeMinutes} min"
            },
    )

/**
 * Every [interval], checks whether this service's consumer group keeps up. Events that waited
 * longer than [maxWait] mean the group has stopped (a dead loop, a stuck handler, no consumer at
 * all) or fell far behind: logged as an error and reported to error tracking, where an alert can
 * wake someone. A consumer once stood still for 44 hours unnoticed; now that is noticed within
 * [maxWait] plus [interval]. Runs only while the service serves, never in cron mode.
 */
class ConsumerLagWatch(
    private val groupId: String,
    private val lag: ConsumerLag,
    private val analytics: ProductAnalytics,
    private val interval: Duration = 5.minutes,
    private val maxWait: Duration = 10.minutes,
) : LifecycleComponent {
    override val name = "consumer-lag-watch:$groupId"
    override val order = LifecycleOrder.TRAFFIC

    private val log = LoggerFactory.getLogger(ConsumerLagWatch::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override suspend fun onReady() {
        job =
            scope.launch {
                while (isActive) {
                    delay(interval)
                    check()
                }
            }
    }

    /** One check; the stalled partitions, if any, already reported. */
    suspend fun check(): List<PartitionLag> {
        val stalled =
            try {
                withContext(Dispatchers.IO) { lag.of(groupId) }.filter { it.waited > maxWait }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not read the lag of consumer group {}", groupId, e)
                return emptyList()
            }
        if (stalled.isNotEmpty()) {
            val error = ConsumerStalledException(groupId, stalled)
            log.error(error.message)
            analytics.captureException(
                error,
                mapOf(
                    "consumer_group" to groupId,
                    "waiting_events" to stalled.sumOf { it.events },
                    "oldest_wait_minutes" to stalled.maxOf { it.waited.inWholeMinutes },
                ),
            )
        }
        return stalled
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
    }
}
