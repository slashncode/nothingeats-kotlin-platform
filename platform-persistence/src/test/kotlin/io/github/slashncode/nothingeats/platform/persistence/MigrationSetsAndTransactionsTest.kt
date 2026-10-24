package io.github.slashncode.nothingeats.platform.persistence

import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

private object Extras : Table("extras") {
    val id = integer("id")
}

private object Numbers : Table("dishes") {
    val id = integer("id")
    val name = text("name")
}

private val extraSet = MigrationSet("extra", listOf("classpath:db/extra"), "flyway_history_extra")

class MigrationSetsAndTransactionsTest :
    FunSpec({
        test("migration sets are applied with separate history tables, in either order") {
            val config = PostgresTestDatabases.create()
            val migrations = FlywayMigrations(config, listOf(MigrationSet.SERVICE, extraSet))

            migrations.migrate()

            val database = PostgresDatabase(config, migrations).also { it.start() }
            database.transaction {
                Extras.insert { it[id] = 1 }
                Numbers.insert {
                    it[id] = 1
                    it[name] = "Phantom Pizza"
                }
            }
            database.stop()
        }

        test("a missing set counts as pending") {
            val config = PostgresTestDatabases.create()
            FlywayMigrations(config).migrate()

            val error =
                shouldThrow<IllegalStateException> {
                    PostgresDatabase(
                        config,
                        FlywayMigrations(config, listOf(MigrationSet.SERVICE, extraSet)),
                    ).start()
                }

            error.message shouldContain "V1__create_extras.sql"
        }

        test("a nested transaction joins the outer one, so both roll back together") {
            val config = PostgresTestDatabases.create()
            FlywayMigrations(config).migrate()
            val database = PostgresDatabase(config).also { it.start() }

            shouldThrow<IllegalStateException> {
                database.transaction {
                    Numbers.insert {
                        it[id] = 1
                        it[name] = "outer"
                    }
                    database.transaction {
                        Numbers.insert {
                            it[id] = 2
                            it[name] = "inner"
                        }
                    }
                    error("rollback everything")
                }
            }

            database.transaction(readOnly = true) { Numbers.selectAll().map { it[Numbers.name] } } shouldContainExactly emptyList()
            database.transaction {
                Numbers.insert {
                    it[id] = 3
                    it[name] = "committed"
                }
            }
            database.transaction(readOnly = true) { Numbers.selectAll().count() } shouldBe 1L
            database.stop()
        }
    })
