package io.github.slashncode.nothingeats.platform.valkey

import com.sksamuel.hoplite.Secret
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthCheck
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthStatus
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

data class ValkeyConfig(
    val host: String = "localhost",
    /** The local cluster's Valkey on the host; in the cluster the service listens on 6379. */
    val port: Int = 16379,
    val username: String = "default",
    val password: Secret? = null,
    val timeout: Duration = 2.seconds,
)

internal fun ValkeyConfig.redisUri(): RedisURI =
    RedisURI.Builder
        .redis(host, port)
        .withTimeout(timeout.toJavaDuration())
        .apply { password?.let { withAuthentication(username, it.value) } }
        .build()

/**
 * The service's Valkey connection: one Lettuce connection, thread-safe and multiplexed, that
 * reconnects on its own after it was established once.
 *
 * Whether Valkey is a readiness [HealthCheck] is the service's choice: a cache or rate limiter that
 * fails open should not take the service out of rotation.
 */
class Valkey(
    private val config: ValkeyConfig,
) : LifecycleComponent,
    HealthCheck {
    override val name = "valkey"
    override val order = LifecycleOrder.INFRASTRUCTURE

    @Volatile private var client: RedisClient? = null

    @Volatile private var connection: StatefulRedisConnection<String, String>? = null

    override suspend fun start() =
        withContext(Dispatchers.IO) {
            val redis = RedisClient.create(config.redisUri())
            try {
                connection = redis.connect()
                client = redis
            } catch (e: Exception) {
                redis.shutdown()
                throw e
            }
        }

    override suspend fun stop() {
        val open = connection
        val redis = client
        connection = null
        client = null
        open?.closeAsync()?.await()
        redis?.shutdownAsync()?.await()
    }

    override suspend fun check(): HealthStatus {
        val commands = connection?.async() ?: return HealthStatus.down("not started")
        return if (commands.ping().await() == "PONG") HealthStatus.UP else HealthStatus.down("unexpected ping reply")
    }

    /** Async commands; await the returned futures with `kotlinx.coroutines.future.await`. */
    fun commands(): RedisAsyncCommands<String, String> = connection?.async() ?: error("Valkey is not started")
}
