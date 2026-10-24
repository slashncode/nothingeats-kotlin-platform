package io.github.slashncode.nothingeats.platform.testing

import com.sksamuel.hoplite.Secret
import io.github.slashncode.nothingeats.platform.persistence.PostgresConfig
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID

/**
 * One Postgres container per test JVM, one fresh database per call: full isolation between
 * tests without paying a container start each time. Testcontainers removes the container on exit.
 */
object PostgresTestDatabases {
    private const val IMAGE = "postgres:18-alpine"
    private const val POSTGRES_PORT = 5432

    private val container: PostgreSQLContainer by lazy { PostgreSQLContainer(IMAGE).apply { start() } }

    fun create(): PostgresConfig {
        val name = "test_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return PostgresConfig(
            url = "jdbc:postgresql://${container.host}:${container.getMappedPort(POSTGRES_PORT)}/$name",
            user = container.username,
            password = Secret(container.password),
        )
    }
}
