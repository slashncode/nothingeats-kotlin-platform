package io.github.slashncode.nothingeats.platform.grpc

import com.google.rpc.BadRequest
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.error.Violation
import io.github.slashncode.nothingeats.platform.grpc.test.v1.EchoRequest
import io.github.slashncode.nothingeats.platform.grpc.test.v1.EchoResponse
import io.github.slashncode.nothingeats.platform.grpc.test.v1.EchoServiceGrpcKt
import io.github.slashncode.nothingeats.platform.grpc.test.v1.FailRequest
import io.github.slashncode.nothingeats.platform.grpc.test.v1.FailResponse
import io.github.slashncode.nothingeats.platform.grpc.test.v1.WhoAmIRequest
import io.github.slashncode.nothingeats.platform.grpc.test.v1.WhoAmIResponse
import io.github.slashncode.nothingeats.platform.grpc.test.v1.echoResponse
import io.github.slashncode.nothingeats.platform.grpc.test.v1.whoAmIResponse
import io.github.slashncode.nothingeats.platform.testing.TestTokens
import io.grpc.StatusException
import io.grpc.health.v1.HealthCheckRequest
import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import io.grpc.health.v1.HealthGrpc
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.protobuf.StatusProto
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

private class EchoService : EchoServiceGrpcKt.EchoServiceCoroutineImplBase() {
    override suspend fun echo(request: EchoRequest): EchoResponse {
        delay(request.delayMillis)
        return echoResponse { text = request.text }
    }

    override suspend fun whoAmI(request: WhoAmIRequest): WhoAmIResponse = whoAmIResponse { userId = GrpcCaller.current().id }

    override suspend fun fail(request: FailRequest): FailResponse =
        when (request.kind) {
            "not-found" -> throw AppError.NotFound(ErrorCode("DISH_NOT_FOUND"), "No such dish")
            "invalid" -> throw AppError.Invalid(ErrorCode("QUOTE_INVALID"), "Invalid quote", listOf(Violation("latitude", "out of range")))
            else -> error("database password is hunter2")
        }
}

private class Running(
    val server: GrpcServer,
    val channel: GrpcChannel,
) {
    val echo get() = EchoServiceGrpcKt.EchoServiceCoroutineStub(channel.channel)
}

private val tokens = TestTokens()

private suspend fun running(
    deadlineMillis: Long = 2_000,
    reported: MutableList<Throwable> = mutableListOf(),
): Running {
    val name = "grpc-test-${UUID.randomUUID()}"
    val server =
        GrpcServer(
            GrpcServerConfig(),
            setOf(EchoService()),
            AuthInterceptor(tokens.jwtAuth, publicMethods = setOf("nothingeats.test.v1.EchoService/Echo")),
            ErrorInterceptor { reported += it },
        ) { InProcessServerBuilder.forName(name).directExecutor() }
    server.onReady()
    val channel =
        GrpcChannel(GrpcClientConfig(target = name, deadline = deadlineMillis.milliseconds)) { InProcessChannelBuilder.forName(name) }
    return Running(server, channel)
}

class GrpcTest :
    FunSpec({
        test("methods need a valid token unless they are public; the caller is known in the handler") {
            val grpc = running()

            grpc.channel
                .prepare(grpc.echo)
                .echo(EchoRequest.newBuilder().setText("hi").build())
                .text shouldBe "hi"
            shouldThrow<StatusException> { grpc.channel.prepare(grpc.echo).whoAmI(WhoAmIRequest.getDefaultInstance()) }
                .status.code shouldBe io.grpc.Status.Code.UNAUTHENTICATED
            shouldThrow<StatusException> {
                grpc.channel.prepare(grpc.echo, tokens.accessToken(audience = "elsewhere")).whoAmI(WhoAmIRequest.getDefaultInstance())
            }.status.code shouldBe io.grpc.Status.Code.UNAUTHENTICATED
            grpc.channel
                .prepare(grpc.echo, tokens.accessToken(userId = "u-9"))
                .whoAmI(WhoAmIRequest.getDefaultInstance())
                .userId shouldBe "u-9"
            grpc.server.stop()
        }

        test("domain errors keep their stable code and violations; unexpected ones leak nothing") {
            val reported = mutableListOf<Throwable>()
            val grpc = running(reported = reported)
            val stub = grpc.channel.prepare(grpc.echo, tokens.accessToken())

            val notFound = shouldThrow<StatusException> { stub.fail(FailRequest.newBuilder().setKind("not-found").build()) }
            notFound.status.code shouldBe io.grpc.Status.Code.NOT_FOUND
            notFound.errorCode() shouldBe "DISH_NOT_FOUND"

            val invalid = shouldThrow<StatusException> { stub.fail(FailRequest.newBuilder().setKind("invalid").build()) }
            invalid.status.code shouldBe io.grpc.Status.Code.INVALID_ARGUMENT
            StatusProto
                .fromThrowable(invalid)!!
                .detailsList
                .first { it.`is`(BadRequest::class.java) }
                .unpack(BadRequest::class.java)
                .getFieldViolations(0)
                .field shouldBe "latitude"

            val unexpected = shouldThrow<StatusException> { stub.fail(FailRequest.newBuilder().setKind("boom").build()) }
            unexpected.status.code shouldBe io.grpc.Status.Code.INTERNAL
            unexpected.status.description.orEmpty() shouldNotContain "hunter2"
            unexpected.errorCode() shouldBe null
            reported.single().message shouldBe "database password is hunter2"
            grpc.server.stop()
        }

        test("every call has a deadline") {
            val grpc = running(deadlineMillis = 100)

            shouldThrow<StatusException> {
                grpc.channel.prepare(grpc.echo).echo(
                    EchoRequest
                        .newBuilder()
                        .setText("slow")
                        .setDelayMillis(1_000)
                        .build(),
                )
            }.status.code shouldBe io.grpc.Status.Code.DEADLINE_EXCEEDED
            grpc.server.stop()
        }

        test("the health service says SERVING while running and needs no token") {
            val grpc = running()
            val health = HealthGrpc.newBlockingStub(grpc.channel.channel)

            health.check(HealthCheckRequest.getDefaultInstance()).status shouldBe ServingStatus.SERVING
            health.check(HealthCheckRequest.newBuilder().setService("nothingeats.test.v1.EchoService").build()).status shouldBe
                ServingStatus.SERVING
            grpc.server.stop()
            grpc.channel.stop()
        }
    })
