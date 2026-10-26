package io.github.slashncode.nothingeats.platform.grpc

import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import io.grpc.CallCredentials
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.AbstractStub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class GrpcClientConfig(
    /** `dns:///delivery-service:9090` in the cluster; the DNS resolver gives every pod to the client. */
    val target: String,
    /** Every call has a deadline; the caller decides what to do when it passes. */
    val deadline: Duration = 2.seconds,
    /** Attempts for UNAVAILABLE (connection trouble), the one status that is always safe to retry; 1 = no retries. */
    val maxAttempts: Int = 3,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }
}

/**
 * One channel to another service, closed with the service. Calls go out with [GrpcClientConfig.deadline]
 * and are retried by gRPC itself on UNAVAILABLE with exponential backoff.
 */
class GrpcChannel(
    val config: GrpcClientConfig,
    // round_robin spreads calls over every pod the DNS name resolves to (a headless service).
    builder: () -> ManagedChannelBuilder<*> = {
        NettyChannelBuilder.forTarget(config.target).usePlaintext().defaultLoadBalancingPolicy("round_robin")
    },
) : LifecycleComponent {
    override val name = "grpc-channel:${config.target}"
    override val order = LifecycleOrder.INFRASTRUCTURE

    // gRPC refuses a retry policy with a single attempt, so "no retries" means no policy at all.
    val channel: ManagedChannel =
        builder()
            .apply {
                if (config.maxAttempts > 1) {
                    defaultServiceConfig(retryPolicy(config.maxAttempts))
                    enableRetry()
                } else {
                    disableRetry()
                }
            }.build()

    /** [stub] with the configured deadline and the end user's token, if the call is on their behalf. */
    fun <S : AbstractStub<S>> prepare(
        stub: S,
        accessToken: String? = null,
    ): S {
        val withDeadline = stub.withDeadlineAfter(config.deadline.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        return accessToken?.let { withDeadline.withCallCredentials(BearerToken(it)) } ?: withDeadline
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            channel.shutdown()
            if (!channel.awaitTermination(SHUTDOWN.inWholeMilliseconds, TimeUnit.MILLISECONDS)) channel.shutdownNow()
        }
    }

    private companion object {
        val SHUTDOWN = 5.seconds
        val INITIAL_BACKOFF = 100.milliseconds
        val MAX_BACKOFF = 1.seconds

        // gRPC service config: applies to every method of every service on this channel.
        fun retryPolicy(maxAttempts: Int): Map<String, Any> =
            mapOf(
                "methodConfig" to
                    listOf(
                        mapOf(
                            "name" to listOf(emptyMap<String, Any>()),
                            "retryPolicy" to
                                mapOf(
                                    "maxAttempts" to maxAttempts.toDouble(),
                                    "initialBackoff" to "${INITIAL_BACKOFF.inWholeMilliseconds / 1000.0}s",
                                    "maxBackoff" to "${MAX_BACKOFF.inWholeMilliseconds / 1000.0}s",
                                    "backoffMultiplier" to 2.0,
                                    "retryableStatusCodes" to listOf(Status.Code.UNAVAILABLE.name),
                                ),
                        ),
                    ),
            )
    }
}

/** Forwards the end user's access token, so the called service authorizes the user itself (JWKS). */
class BearerToken(
    private val token: String,
) : CallCredentials() {
    override fun applyRequestMetadata(
        requestInfo: RequestInfo,
        appExecutor: Executor,
        applier: MetadataApplier,
    ) {
        applier.apply(Metadata().apply { put(AuthInterceptor.AUTHORIZATION, "Bearer $token") })
    }
}
