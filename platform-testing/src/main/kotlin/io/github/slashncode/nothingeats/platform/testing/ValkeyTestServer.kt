package io.github.slashncode.nothingeats.platform.testing

import com.sksamuel.hoplite.Secret
import io.github.slashncode.nothingeats.platform.valkey.ValkeyConfig
import org.testcontainers.containers.GenericContainer

/**
 * One Valkey per test JVM (same version as the local cluster), with a password like the cluster's.
 * Tests keep apart by key prefix, e.g. a random `RateLimiter` prefix per test.
 */
object ValkeyTestServer {
    private const val IMAGE = "valkey/valkey:9.1.2"
    private const val PASSWORD = "test-password"
    private const val PORT = 6379

    private val container: GenericContainer<*> by lazy {
        GenericContainer(IMAGE)
            .withExposedPorts(PORT)
            .withCommand("valkey-server", "--requirepass", PASSWORD)
            .apply { start() }
    }

    fun config(): ValkeyConfig = ValkeyConfig(host = container.host, port = container.getMappedPort(PORT), password = Secret(PASSWORD))
}
