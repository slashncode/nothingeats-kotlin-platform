package io.github.slashncode.nothingeats.platform.posthog

import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.http.auth.public
import io.github.slashncode.nothingeats.platform.http.installPlatform
import io.github.slashncode.nothingeats.platform.testing.RecordingAnalytics
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json

class ClientFlagsTest :
    FunSpec({
        val analytics =
            RecordingAnalytics().apply {
                flags["checkout-copy"] = "calm"
                payloads["checkout-copy"] = """{"button":"Nichts bestellen"}"""
                flags["new-badges"] = true
                flags["coach-placement"] = false
                flags["spin-wheel-odds"] = "generous"
            }

        fun flagsApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    installPlatform(Readiness(), HealthChecks(emptySet()))
                    with(PostHogHttpExtension()) { install() }
                    routing {
                        public {
                            route("/api/identity/v1") {
                                clientFlags(analytics, listOf("checkout-copy", "new-badges", "coach-placement", "unknown"))
                            }
                        }
                    }
                }
                block()
            }

        test("the browser's flags with their payloads, never the server's, and nothing cached") {
            flagsApp {
                val response = client.get("/api/identity/v1/flags") { header("X-PostHog-Distinct-Id", "anon-1") }

                Json.parseToJsonElement(response.bodyAsText()) shouldBe
                    Json.parseToJsonElement(
                        """{"flags":{"checkout-copy":"calm","new-badges":true},"payloads":{"checkout-copy":{"button":"Nichts bestellen"}}}""",
                    )
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                analytics.events shouldBe emptyList()
            }
        }

        test("without consent (no distinct id) there are no flags") {
            flagsApp {
                val body = client.get("/api/identity/v1/flags").bodyAsText()

                Json.parseToJsonElement(body) shouldBe Json.parseToJsonElement("""{"flags":{},"payloads":{}}""")
            }
        }
    })
