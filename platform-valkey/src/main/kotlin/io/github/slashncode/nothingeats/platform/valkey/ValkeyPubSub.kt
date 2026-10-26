package io.github.slashncode.nothingeats.platform.valkey

import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthCheck
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthStatus
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

data class ValkeyMessage(
    val channel: String,
    val message: String,
)

/**
 * Fan-out between pods with Valkey pub/sub: [publish] reaches every pod that is subscribed to a
 * matching pattern at that moment, at most once, and nothing is stored. Meant for live updates
 * whose source of truth lives elsewhere (a browser that missed one reloads the state).
 *
 * Subscribes to [patterns] on start; Lettuce subscribes again after a reconnect. A slow collector
 * loses the oldest messages instead of blocking the connection.
 */
class ValkeyPubSub(
    private val config: ValkeyConfig,
    private val patterns: Set<String>,
) : LifecycleComponent,
    HealthCheck {
    override val name = "valkey-pubsub"
    override val order = LifecycleOrder.INFRASTRUCTURE

    private val incoming = MutableSharedFlow<ValkeyMessage>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Messages on the subscribed patterns, as they arrive. */
    val messages: SharedFlow<ValkeyMessage> = incoming

    @Volatile private var client: RedisClient? = null

    @Volatile private var publisher: StatefulRedisConnection<String, String>? = null

    @Volatile private var subscriber: StatefulRedisPubSubConnection<String, String>? = null

    override suspend fun start() {
        val redis = RedisClient.create(config.redisUri())
        try {
            withContext(Dispatchers.IO) {
                publisher = redis.connect()
                subscriber =
                    redis.connectPubSub().also { connection ->
                        connection.addListener(
                            object : RedisPubSubAdapter<String, String>() {
                                override fun message(
                                    pattern: String,
                                    channel: String,
                                    message: String,
                                ) {
                                    incoming.tryEmit(ValkeyMessage(channel, message))
                                }
                            },
                        )
                    }
            }
            if (patterns.isNotEmpty()) subscriber!!.async().psubscribe(*patterns.toTypedArray()).await()
            client = redis
        } catch (e: Exception) {
            redis.shutdown()
            throw e
        }
    }

    /** Returns how many subscribers (pods) received the message. */
    suspend fun publish(
        channel: String,
        message: String,
    ): Long = (publisher ?: error("Valkey pub/sub is not started")).async().publish(channel, message).await()

    override suspend fun check(): HealthStatus {
        val commands = publisher?.async() ?: return HealthStatus.down("not started")
        return if (commands.ping().await() == "PONG") HealthStatus.UP else HealthStatus.down("unexpected ping reply")
    }

    override suspend fun stop() {
        val sub = subscriber
        val pub = publisher
        val redis = client
        subscriber = null
        publisher = null
        client = null
        sub?.closeAsync()?.await()
        pub?.closeAsync()?.await()
        redis?.shutdownAsync()?.await()
    }

    private companion object {
        const val BUFFER = 1024
    }
}
