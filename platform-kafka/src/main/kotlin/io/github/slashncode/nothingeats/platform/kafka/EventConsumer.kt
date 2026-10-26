package io.github.slashncode.nothingeats.platform.kafka

import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthCheck
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthStatus
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.kafka.clients.consumer.CloseOptions
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.WakeupException
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Handles the events of one topic. Contributed via DI multibinding. */
interface EventHandler {
    val topic: String

    /**
     * Runs inside a database transaction together with the inbox entry, so database changes made
     * here happen exactly once per event. Effects outside the database (sending a mail) are
     * at-least-once. Throwing makes the consumer retry, then park the event in the DLQ.
     */
    suspend fun handle(event: CloudEvent)

    /** With the record's position in Kafka; defaults to [handle]. */
    suspend fun handle(
        event: CloudEvent,
        position: EventPosition,
    ) = handle(event)
}

/** Where a record sits in Kafka. Per partition, and so per key, the offset only grows. */
data class EventPosition(
    val topic: String,
    val partition: Int,
    val offset: Long,
)

/**
 * For consumers without an inbox (`EventConsumer(db = null)`) whose writes are idempotent and
 * ordered by themselves, e.g. search documents with the offset as external version. Every event
 * may arrive more than once.
 */
interface PositionedEventHandler : EventHandler {
    suspend fun handleAt(
        event: CloudEvent,
        position: EventPosition,
    )

    override suspend fun handle(
        event: CloudEvent,
        position: EventPosition,
    ) = handleAt(event, position)

    override suspend fun handle(event: CloudEvent): Unit = error("${this::class.simpleName} needs the record position")
}

data class EventConsumerConfig(
    /** The consumer group, always the service name, so every service gets every event. */
    val groupId: String,
    val maxAttempts: Int = 3,
    val retryBackoff: Duration = 1.seconds,
    val pollTimeout: Duration = 1.seconds,
) {
    /** Events that failed [maxAttempts] times end up here, with the original topic and error as headers. */
    val deadLetterTopic: String get() = "$groupId.dlq"
}

/**
 * Consumes the topics of all [handlers] in one consumer group. Records are handled one by one per
 * poll (keeping per-key order); offsets are committed only after every record of the poll was
 * handled or written to the dead-letter topic, so a crash means redelivery, never loss.
 */
class EventConsumer(
    private val config: EventConsumerConfig,
    private val handlers: Set<EventHandler>,
    /** The inbox for exactly-once database effects; null when every handler is idempotent itself. */
    private val db: PostgresDatabase?,
    private val consumerFactory: () -> Consumer<String, ByteArray>,
    private val deadLetters: Producer<String, ByteArray>,
    private val clock: Clock = Clock.systemUTC(),
) : LifecycleComponent,
    HealthCheck {
    override val name = "event-consumer:${config.groupId}"
    override val order = LifecycleOrder.TRAFFIC

    private val log = LoggerFactory.getLogger(EventConsumer::class.java)
    private val handlerByTopic = handlers.associateBy { it.topic }

    // KafkaConsumer is not thread-safe: everything touching it runs on this one thread.
    private val consumerThread = Executors.newSingleThreadExecutor { Thread(it, "kafka-consumer-${config.groupId}") }
    private val scope = CoroutineScope(SupervisorJob() + consumerThread.asCoroutineDispatcher())
    private var consumer: Consumer<String, ByteArray>? = null
    private var job: Job? = null

    @Volatile private var failure: Throwable? = null

    init {
        require(handlerByTopic.size == handlers.size) { "Only one handler per topic: ${handlers.map { it.topic }}" }
    }

    override suspend fun onReady() {
        if (handlers.isEmpty()) return
        job =
            scope.launch {
                val kafka = consumerFactory().also { consumer = it }
                kafka.subscribe(handlerByTopic.keys)
                try {
                    consumeLoop(kafka)
                } catch (e: WakeupException) {
                    log.debug("Consumer woken up for shutdown")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failure = e
                    log.error("Event consumer {} stopped", config.groupId, e)
                } finally {
                    kafka.close(CloseOptions.timeout(java.time.Duration.ofSeconds(CLOSE_TIMEOUT_SECONDS)))
                }
            }
    }

    override suspend fun stop() {
        consumer?.wakeup()
        job?.cancelAndJoin()
        consumerThread.shutdown()
    }

    override suspend fun check(): HealthStatus =
        failure?.let { HealthStatus.down("consumer stopped: ${it::class.simpleName}") } ?: HealthStatus.UP

    private suspend fun consumeLoop(kafka: Consumer<String, ByteArray>) {
        while (scope.isActive) {
            val records = kafka.poll(config.pollTimeout.toJavaDuration())
            for (record in records) {
                process(record)
            }
            if (!records.isEmpty) kafka.commitSync()
        }
    }

    private suspend fun process(record: ConsumerRecord<String, ByteArray>) {
        val event =
            try {
                EventJson.decodeFromString(CloudEvent.serializer(), record.value().decodeToString())
            } catch (e: Exception) {
                deadLetter(record, e)
                return
            }
        val handler = handlerByTopic.getValue(record.topic())
        val position = EventPosition(record.topic(), record.partition(), record.offset())
        for (attempt in 1..config.maxAttempts) {
            try {
                if (db == null) {
                    handler.handle(event, position)
                    return
                }
                db.transaction {
                    val firstTime =
                        InboxEvents
                            .insertIgnore {
                                it[consumer] = config.groupId
                                it[eventId] = event.id
                                it[processedAt] = clock.instant()
                            }.insertedCount > 0
                    if (firstTime) handler.handle(event, position) else log.info("Skipping duplicate event {} ({})", event.id, event.type)
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == config.maxAttempts) {
                    deadLetter(record, e)
                    return
                }
                log.warn("Handling {} failed (attempt {}/{})", event.type, attempt, config.maxAttempts, e)
                delay(config.retryBackoff * attempt)
            }
        }
    }

    /** Waits for the broker's acknowledgement: the offset is only committed once the event is safe. */
    private fun deadLetter(
        record: ConsumerRecord<String, ByteArray>,
        error: Exception,
    ) {
        log.error("Moving record {}-{}@{} to {}", record.topic(), record.partition(), record.offset(), config.deadLetterTopic, error)
        val dead = ProducerRecord(config.deadLetterTopic, record.key(), record.value())
        dead.headers().apply {
            add("dlq-original-topic", record.topic().toByteArray())
            add("dlq-original-partition", record.partition().toString().toByteArray())
            add("dlq-original-offset", record.offset().toString().toByteArray())
            add("dlq-error", "${error::class.qualifiedName}: ${error.message}".take(MAX_ERROR_LENGTH).toByteArray())
        }
        deadLetters.send(dead).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val CLOSE_TIMEOUT_SECONDS = 5L
        const val SEND_TIMEOUT_SECONDS = 30L
        const val MAX_ERROR_LENGTH = 500
    }
}
