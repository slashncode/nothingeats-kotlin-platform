package io.github.slashncode.nothingeats.platform.http.auth

import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.http.PlatformJson
import io.github.slashncode.nothingeats.platform.http.Problem
import io.github.slashncode.nothingeats.platform.http.installPlatform
import io.github.slashncode.nothingeats.platform.testing.TestTokens
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.minutes

private val tokens = TestTokens()

private fun api(block: suspend ApplicationTestBuilder.() -> Unit) =
    testApplication {
        application {
            installPlatform(Readiness(), HealthChecks(emptySet()), tokens.jwtAuth)
            routing {
                public { get("/menu") { call.respondText("open") } }
                authenticated { get("/me") { call.respondText(call.user.id + " " + call.user.locale) } }
                requireRole("admin") { get("/admin") { call.respondText("admin") } }
            }
        }
        block()
    }

private suspend fun HttpResponse.code() = PlatformJson.decodeFromString(Problem.serializer(), bodyAsText()).code

class AuthTest :
    FunSpec({
        test("public routes and health probes need no token") {
            api {
                client.get("/menu").bodyAsText() shouldBe "open"
                client.get("/health/live").status shouldBe HttpStatusCode.OK
            }
        }

        test("authenticated routes accept a valid token and expose the user") {
            api {
                client.get("/me") { bearerAuth(tokens.accessToken(userId = "u-42", locale = "en")) }.bodyAsText() shouldBe "u-42 en"
            }
        }

        test("missing, expired, foreign and forged tokens are rejected with 401") {
            val other = TestTokens()
            api {
                val missing = client.get("/me")
                missing.status shouldBe HttpStatusCode.Unauthorized
                missing.headers[HttpHeaders.WWWAuthenticate] shouldContain "Bearer"
                missing.code() shouldBe "UNAUTHENTICATED"

                val rejected =
                    listOf(
                        tokens.accessToken(expiresIn = (-5).minutes),
                        tokens.accessToken(audience = "someone-else"),
                        tokens.accessToken(issuer = "https://evil.example"),
                        tokens.accessToken(keyId = "unknown-key"),
                        other.accessToken(),
                    )
                rejected.forEach { token -> client.get("/me") { bearerAuth(token) }.status shouldBe HttpStatusCode.Unauthorized }
            }
        }

        test("role-guarded routes answer 403 without the role") {
            api {
                val forbidden = client.get("/admin") { bearerAuth(tokens.accessToken()) }
                forbidden.status shouldBe HttpStatusCode.Forbidden
                forbidden.code() shouldBe "FORBIDDEN"
                client.get("/admin") { bearerAuth(tokens.accessToken(roles = setOf("admin"))) }.bodyAsText() shouldBe "admin"
            }
        }

        test("an unguarded route stops the application from starting") {
            val error =
                shouldThrow<IllegalStateException> {
                    testApplication {
                        application {
                            installPlatform(Readiness(), HealthChecks(emptySet()), tokens.jwtAuth)
                            routing { get("/oops") { call.respondText("exposed") } }
                        }
                        client.get("/oops")
                    }
                }
            error.message shouldContain "/oops"
        }
    })
