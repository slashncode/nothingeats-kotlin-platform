package io.github.slashncode.nothingeats.platform.persistence

import com.sksamuel.hoplite.Secret
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthCheck
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthStatus
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class PostgresConfig(
    val url: String,
    val user: String,
    val password: Secret,
    val maximumPoolSize: Int = 10,
    val connectionTimeout: Duration = 5.seconds,
)

/**
 * The service's Postgres connection pool and Exposed database.
 *
 * On start it refuses to serve against a schema that is behind the code (pending migrations);
 * migrations themselves only run in `migrate` mode. Exposed's blind retry of every SQL error is
 * switched off; use [transactionWithRetry] for the cases where a retry is actually safe.
 */
class PostgresDatabase(
    private val config: PostgresConfig,
    private val migrations: FlywayMigrations? = null,
) : LifecycleComponent,
    HealthCheck {
    override val name = "postgres"
    override val order = LifecycleOrder.INFRASTRUCTURE

    @Volatile private var dataSource: HikariDataSource? = null

    @Volatile private var database: Database? = null

    override suspend fun start() =
        withContext(Dispatchers.IO) {
            val pool = HikariDataSource(config.toHikariConfig())
            try {
                migrations?.ensureUpToDate(pool)
                database = Database.connect(pool, databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 })
                dataSource = pool
            } catch (e: Exception) {
                pool.close()
                throw e
            }
        }

    override suspend fun stop() =
        withContext(Dispatchers.IO) {
            dataSource?.close()
            dataSource = null
            database = null
        }

    override suspend fun check(): HealthStatus =
        withContext(Dispatchers.IO) {
            val pool = dataSource ?: return@withContext HealthStatus.down("not started")
            pool.connection.use { if (it.isValid(VALIDATION_TIMEOUT_SECONDS)) HealthStatus.UP else HealthStatus.down("invalid connection") }
        }

    suspend fun <T> transaction(
        readOnly: Boolean = false,
        block: suspend JdbcTransaction.() -> T,
    ): T {
        val db = database ?: error("Postgres is not started")
        return withContext(Dispatchers.IO) { suspendTransaction(db = db, readOnly = readOnly) { block() } }
    }

    /** Retries only on serialization failures and deadlocks, with a growing pause between attempts. */
    suspend fun <T> transactionWithRetry(
        maxAttempts: Int = 3,
        block: suspend JdbcTransaction.() -> T,
    ): T {
        var attempt = 1
        while (true) {
            try {
                return transaction(block = block)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= maxAttempts || !e.isRetryable()) throw e
                delay(RETRY_BASE_DELAY * attempt)
                attempt++
            }
        }
    }

    private fun PostgresConfig.toHikariConfig() =
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            password = this@toHikariConfig.password.value
            maximumPoolSize = this@toHikariConfig.maximumPoolSize
            connectionTimeout = this@toHikariConfig.connectionTimeout.inWholeMilliseconds
            poolName = "postgres"
        }

    private companion object {
        const val VALIDATION_TIMEOUT_SECONDS = 2
        val RETRY_BASE_DELAY = 50.milliseconds
    }
}
