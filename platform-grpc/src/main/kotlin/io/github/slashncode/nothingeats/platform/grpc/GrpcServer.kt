package io.github.slashncode.nothingeats.platform.grpc

import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import io.grpc.BindableService
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerInterceptors
import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class GrpcServerConfig(
    /** Cluster-internal; the chart exposes it as the `grpc` port of the service. */
    val port: Int = 9090,
    /** In-flight calls get this long to finish on shutdown, then they are cancelled. */
    val shutdownGrace: Duration = 10.seconds,
)

/**
 * The gRPC server of a service: [services] behind authentication and error mapping, plus the
 * standard health service. It opens only after the service is ready (like Kafka consumers) and
 * reports SERVING until shutdown starts.
 *
 * [builder] is replaceable for tests (in-process transport).
 */
class GrpcServer(
    private val config: GrpcServerConfig,
    private val services: Set<BindableService>,
    private val auth: AuthInterceptor,
    private val errors: ErrorInterceptor = ErrorInterceptor(),
    private val builder: () -> ServerBuilder<*> = { NettyServerBuilder.forPort(config.port) },
) : LifecycleComponent {
    override val name = "grpc-server"
    override val order = LifecycleOrder.TRAFFIC

    private val log = LoggerFactory.getLogger(GrpcServer::class.java)
    private val health = HealthStatusManager()
    private var server: Server? = null

    override suspend fun onReady() {
        if (services.isEmpty()) return
        val built =
            builder()
                .apply {
                    // The last interceptor added runs first: authentication, then error mapping around the handler.
                    services.forEach { addService(ServerInterceptors.intercept(it, errors, auth)) }
                    addService(health.healthService)
                }.build()
        withContext(Dispatchers.IO) { built.start() }
        server = built
        services.forEach { health.setStatus(it.bindService().serviceDescriptor.name, ServingStatus.SERVING) }
        health.setStatus(HealthStatusManager.SERVICE_NAME_ALL_SERVICES, ServingStatus.SERVING)
        log.info("gRPC server on port {}: {}", config.port, services.map { it.bindService().serviceDescriptor.name })
    }

    override suspend fun stop() {
        val running = server ?: return
        health.enterTerminalState()
        withContext(Dispatchers.IO) {
            running.shutdown()
            if (!running.awaitTermination(config.shutdownGrace.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                running.shutdownNow().awaitTermination(1, TimeUnit.SECONDS)
            }
        }
    }
}
