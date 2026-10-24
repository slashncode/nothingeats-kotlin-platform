package io.github.slashncode.nothingeats.platform.http

import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.http.auth.JwtAuth
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class HttpServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    /** How long in-flight requests may keep running after stop begins. */
    val shutdownGracePeriod: Duration = 1.seconds,
    /** Hard limit for stopping the server. */
    val shutdownTimeout: Duration = 10.seconds,
)

/** Routes a service contributes to the HTTP server, collected by DI multibinding. */
fun interface HttpRoutes {
    fun Route.register()
}

/** Extra application setup (plugins) contributed by other modules, e.g. ktor-posthog. */
fun interface HttpExtension {
    fun Application.install()
}

/** The Ktor server as a lifecycle component: starts early so health probes answer during startup. */
class HttpServer(
    private val config: HttpServerConfig,
    private val readiness: Readiness,
    private val healthChecks: HealthChecks,
    private val routes: Set<HttpRoutes>,
    private val auth: JwtAuth? = null,
    private val extensions: Set<HttpExtension> = emptySet(),
    private val exceptionReporter: ExceptionReporter? = null,
) : LifecycleComponent {
    override val name = "http-server"
    override val order = LifecycleOrder.HTTP_SERVER

    private var server: EmbeddedServer<*, *>? = null

    override suspend fun start() {
        server =
            embeddedServer(Netty, host = config.host, port = config.port) {
                installPlatform(readiness, healthChecks, auth, exceptionReporter)
                extensions.forEach { with(it) { install() } }
                routing {
                    routes.forEach { with(it) { register() } }
                }
            }.startSuspend(wait = false)
    }

    override suspend fun stop() {
        server?.stopSuspend(
            gracePeriodMillis = config.shutdownGracePeriod.inWholeMilliseconds,
            timeoutMillis = config.shutdownTimeout.inWholeMilliseconds,
        )
        server = null
    }
}
