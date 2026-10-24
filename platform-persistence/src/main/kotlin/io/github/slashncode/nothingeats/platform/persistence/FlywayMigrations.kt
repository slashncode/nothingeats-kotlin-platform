package io.github.slashncode.nothingeats.platform.persistence

import io.github.slashncode.nothingeats.platform.core.run.MigrationRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Flyway migrations from `classpath:db/migration` by default.
 *
 * [migrate] is what `migrate` mode runs (a Helm pre-upgrade Job). Serving pods only call
 * [ensureUpToDate] and refuse to start against an outdated schema.
 */
class FlywayMigrations(
    private val config: PostgresConfig,
    private val locations: List<String> = listOf("classpath:db/migration"),
) : MigrationRunner {
    private val log = LoggerFactory.getLogger(FlywayMigrations::class.java)

    override suspend fun migrate() {
        val result =
            withContext(Dispatchers.IO) {
                flyway(Flyway.configure().dataSource(config.url, config.user, config.password.value)).migrate()
            }
        log.info("Applied {} migration(s), schema version {}", result.migrationsExecuted, result.targetSchemaVersion ?: "unchanged")
    }

    internal fun ensureUpToDate(dataSource: DataSource) {
        val pending = flyway(Flyway.configure().dataSource(dataSource)).info().pending()
        check(pending.isEmpty()) {
            "${pending.size} pending migration(s): ${pending.joinToString { it.script }}. Run the service in 'migrate' mode first."
        }
    }

    private fun flyway(configuration: FluentConfiguration): Flyway =
        configuration
            .locations(*locations.toTypedArray())
            .validateMigrationNaming(true)
            .load()
}
