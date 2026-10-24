package io.github.slashncode.nothingeats.platform.persistence

import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthStatus
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

private object Dishes : Table("dishes") {
    val id = integer("id")
    val name = text("name")
    override val primaryKey = PrimaryKey(id)
}

private suspend fun PostgresDatabase.insertDish(
    id: Int,
    name: String,
) = transaction {
    Dishes.insert {
        it[Dishes.id] = id
        it[Dishes.name] = name
    }
}

private suspend fun migratedDatabase(): PostgresDatabase {
    val config = PostgresTestDatabases.create()
    val migrations = FlywayMigrations(config)
    migrations.migrate()
    return PostgresDatabase(config, migrations).also { it.start() }
}

class PostgresDatabaseTest :
    FunSpec({
        test("refuses to start while migrations are pending") {
            val config = PostgresTestDatabases.create()
            val database = PostgresDatabase(config, FlywayMigrations(config))

            val error = shouldThrow<IllegalStateException> { database.start() }

            error.message shouldContain "V1__create_dishes.sql"
            error.message shouldContain "'migrate' mode"
            database.check() shouldBe HealthStatus.down("not started")
        }

        test("migrate is idempotent") {
            val config = PostgresTestDatabases.create()
            val migrations = FlywayMigrations(config)

            migrations.migrate()
            migrations.migrate()

            PostgresDatabase(config, migrations).start()
        }

        test("runs transactions against the migrated schema") {
            val database = migratedDatabase()

            database.insertDish(1, "Phantom Pizza")
            database.insertDish(2, "Ghost Gyros")

            database.transaction(readOnly = true) { Dishes.selectAll().map { it[Dishes.name] } } shouldContainExactly
                listOf("Phantom Pizza", "Ghost Gyros")
            database.stop()
        }

        test("unique violations are recognised by SQLSTATE and constraint name") {
            val database = migratedDatabase()
            database.insertDish(1, "Phantom Pizza")

            val error = shouldThrow<Exception> { database.insertDish(2, "Phantom Pizza") }

            error.isUniqueViolation() shouldBe true
            error.isRetryable() shouldBe false
            error.violatedConstraint shouldBe "dishes_name_key"
            database.stop()
        }

        test("health check follows the pool") {
            val database = migratedDatabase()
            database.check() shouldBe HealthStatus.UP

            database.stop()

            database.check() shouldBe HealthStatus.down("not started")
        }
    })
