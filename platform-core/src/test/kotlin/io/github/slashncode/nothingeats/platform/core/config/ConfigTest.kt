package io.github.slashncode.nothingeats.platform.core.config

import com.sksamuel.hoplite.ConfigException
import com.sksamuel.hoplite.Secret
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal data class SampleConfig(
    val http: SampleHttp = SampleHttp(),
    val database: SampleDatabase,
)

internal data class SampleHttp(
    val port: Int = 8080,
    val shutdownTimeout: Duration = 10.seconds,
)

internal data class SampleDatabase(
    val url: String,
    val password: Secret,
    val maximumPoolSize: Int = 10,
)

private val required =
    mapOf(
        "DATABASE__URL" to "jdbc:postgresql://localhost:15432/catalog",
        "DATABASE__PASSWORD" to "s3cret",
    )

class ConfigTest :
    FunSpec({
        test("defaults apply when no env var overrides them") {
            val config = loadConfig<SampleConfig>(required)

            config.http.port shouldBe 8080
            config.database.maximumPoolSize shouldBe 10
            config.database.url shouldBe "jdbc:postgresql://localhost:15432/catalog"
        }

        test("double underscore nests, single underscore separates words") {
            val config =
                loadConfig<SampleConfig>(
                    required +
                        mapOf(
                            "HTTP__PORT" to "9000",
                            "HTTP__SHUTDOWN_TIMEOUT" to "30s",
                            "DATABASE__MAXIMUM_POOL_SIZE" to "4",
                        ),
                )

            config.http.port shouldBe 9000
            config.http.shutdownTimeout shouldBe 30.seconds
            config.database.maximumPoolSize shouldBe 4
        }

        test("unrelated env vars are ignored") {
            val config =
                loadConfig<SampleConfig>(
                    required + mapOf("PATH" to "/usr/bin", "HOME" to "/root", "KUBERNETES_SERVICE_HOST" to "10.43.0.1"),
                )

            config.http.port shouldBe 8080
        }

        test("reports every missing value at once") {
            val error =
                shouldThrow<ConfigException> {
                    loadConfig<SampleConfig>(mapOf("DATABASE__MAXIMUM_POOL_SIZE" to "4"))
                }

            error.message shouldContain "url"
            error.message shouldContain "password"
        }

        test("secrets never show up in toString") {
            loadConfig<SampleConfig>(required).toString() shouldNotContain "s3cret"
        }
    })
