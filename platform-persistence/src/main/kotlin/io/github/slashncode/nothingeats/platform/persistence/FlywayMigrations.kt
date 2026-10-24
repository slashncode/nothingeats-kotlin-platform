package io.github.slashncode.nothingeats.platform.persistence

import io.github.slashncode.nothingeats.platform.core.run.MigrationRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * A group of migrations with its own Flyway history table, so platform modules (e.g. the Kafka
 * outbox) can ship tables without their version numbers colliding with the service's own.
 */
data class MigrationSet(
    val name: String,
    val locations: List<String>,
    val historyTable: String,
) {
    companion object {
        /** The service's own migrations in `db/migration`. */
        val SERVICE = MigrationSet("service", listOf("classpath:db/migration"), "flyway_schema_history")
    }
}

/**
 * Flyway migrations, applied set by set in the given order.
 *
 * [migrate] is what `migrate` mode runs (a Helm pre-upgrade Job). Serving pods only call
 * [ensureUpToDate] and refuse to start against an outdated schema.
 */
class FlywayMigrations(
    private val config: PostgresConfig,
    private val sets: List<MigrationSet> = listOf(MigrationSet.SERVICE),
) : MigrationRunner {
    private val log = LoggerFactory.getLogger(FlywayMigrations::class.java)

    override suspend fun migrate() {
        withContext(Dispatchers.IO) {
            sets.forEach { set ->
                val result = flyway(Flyway.configure().dataSource(config.url, config.user, config.password.value), set).migrate()
                log.info(
                    "Applied {} {} migration(s), schema version {}",
                    result.migrationsExecuted,
                    set.name,
                    result.targetSchemaVersion ?: "unchanged",
                )
            }
        }
    }

    internal fun ensureUpToDate(dataSource: DataSource) {
        val pending = sets.flatMap { set -> flyway(Flyway.configure().dataSource(dataSource), set).info().pending().toList() }
        check(pending.isEmpty()) {
            "${pending.size} pending migration(s): ${pending.joinToString { it.script }}. Run the service in 'migrate' mode first."
        }
    }

    // Each set baselines at 0 when the schema already holds tables of another set.
    private fun flyway(
        configuration: FluentConfiguration,
        set: MigrationSet,
    ): Flyway =
        configuration
            .locations(*set.locations.toTypedArray())
            .table(set.historyTable)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .validateMigrationNaming(true)
            .load()
}
