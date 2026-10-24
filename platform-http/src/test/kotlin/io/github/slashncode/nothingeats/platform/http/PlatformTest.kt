package io.github.slashncode.nothingeats.platform.http

import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.error.Violation
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthCheck
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthStatus
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleManager
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.http.auth.public
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class Order(
    val dishId: String,
)

private fun check(
    name: String,
    status: HealthStatus,
) = object : HealthCheck {
    override val name = name

    override suspend fun check() = status
}

private fun Application.sampleRoutes() {
    routing {
        public { sampleEndpoints() }
    }
}

private fun Route.sampleEndpoints() {
    get("/dishes/{id}") { throw AppError.NotFound(ErrorCode("DISH_NOT_FOUND"), "Dish ${call.parameters["id"]} does not exist") }
    get("/invalid") {
        throw AppError.Invalid(ErrorCode("ORDER_INVALID"), "Order is invalid", listOf(Violation("quantity", "must be positive")))
    }
    get("/limited") { throw AppError.RateLimited(retryAfter = 30.seconds) }
    get("/boom") { error("database password is hunter2") }
    post("/orders") { call.receive<Order>() }
}

private suspend fun HttpResponse.problem(): Problem = PlatformJson.decodeFromString(Problem.serializer(), bodyAsText())

private fun platformTest(
    readiness: Readiness = Readiness(),
    checks: Set<HealthCheck> = emptySet(),
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application {
        installPlatform(readiness, HealthChecks(checks))
        sampleRoutes()
    }
    block()
}

private suspend fun readyReadiness() = Readiness().also { LifecycleManager(emptySet(), it).start() }

class PlatformTest :
    FunSpec({
        test("AppError becomes a problem+json response with its code") {
            platformTest {
                val response = client.get("/dishes/42") { header(HttpHeaders.XRequestId, "req-1") }

                response.status shouldBe HttpStatusCode.NotFound
                response.contentType()?.withoutParameters() shouldBe ProblemJson
                response.headers[HttpHeaders.XRequestId] shouldBe "req-1"
                response.problem() shouldBe
                    Problem(
                        title = "Not Found",
                        status = 404,
                        detail = "Dish 42 does not exist",
                        instance = "/dishes/42",
                        code = "DISH_NOT_FOUND",
                        requestId = "req-1",
                    )
            }
        }

        test("validation errors list their violations") {
            platformTest {
                val problem = client.get("/invalid").problem()

                problem.status shouldBe 422
                problem.violations shouldBe listOf(ProblemViolation("quantity", "must be positive"))
            }
        }

        test("rate limiting sets Retry-After") {
            platformTest {
                val response = client.get("/limited")

                response.status shouldBe HttpStatusCode.TooManyRequests
                response.headers[HttpHeaders.RetryAfter] shouldBe "30"
            }
        }

        test("unexpected errors never leak their message") {
            platformTest {
                val response = client.get("/boom")

                response.status shouldBe HttpStatusCode.InternalServerError
                response.bodyAsText() shouldNotContain "hunter2"
                response.problem().code shouldBe "INTERNAL_ERROR"
            }
        }

        test("unknown routes and unreadable bodies are problems too") {
            platformTest {
                client.get("/nope").problem().code shouldBe "NOT_FOUND"

                val response =
                    client.post("/orders") {
                        contentType(ContentType.Application.Json)
                        setBody("{not json")
                    }
                response.status shouldBe HttpStatusCode.BadRequest
                response.problem().code shouldBe "BAD_REQUEST"
            }
        }

        test("a request id is generated when the client sends none") {
            platformTest {
                client.get("/health/live").headers[HttpHeaders.XRequestId] shouldNotBe null
            }
        }

        test("liveness is up while starting, readiness is not") {
            platformTest {
                client.get("/health/live").status shouldBe HttpStatusCode.OK
                client.get("/health/ready").status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }

        test("readiness follows the lifecycle and every health check") {
            platformTest(readyReadiness(), setOf(check("postgres", HealthStatus.UP))) {
                client.get("/health/ready").status shouldBe HttpStatusCode.OK
            }
            platformTest(readyReadiness(), setOf(check("postgres", HealthStatus.down("connection refused")))) {
                val response = client.get("/health/ready")

                response.status shouldBe HttpStatusCode.ServiceUnavailable
                PlatformJson.decodeFromString(HealthResponse.serializer(), response.bodyAsText()).checks shouldBe
                    mapOf("postgres" to CheckResponse("DOWN", "connection refused"))
            }
        }
    })
