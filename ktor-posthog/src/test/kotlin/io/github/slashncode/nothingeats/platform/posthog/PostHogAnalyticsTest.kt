package io.github.slashncode.nothingeats.platform.posthog

import com.sksamuel.hoplite.Secret
import io.github.slashncode.nothingeats.platform.core.analytics.ErasureResult
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.http.auth.public
import io.github.slashncode.nothingeats.platform.http.installPlatform
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content

private fun JsonObject.property(key: String) = this["properties"]?.jsonObject?.string(key)

class PostHogAnalyticsTest :
    FunSpec({
        test("server events carry the browser's distinct id and session, exceptions go to error tracking") {
            FakePostHog().use { posthog ->
                val analytics = PostHogAnalytics(PostHogSettings(projectToken = "phc_test", host = posthog.url))
                testApplication {
                    application {
                        installPlatform(Readiness(), HealthChecks(emptySet()), exceptionReporter = PostHogExceptionReporter(analytics))
                        with(PostHogHttpExtension()) { install() }
                        routing {
                            public {
                                get("/dishes") {
                                    analytics.capture("dish_list_viewed", mapOf("count" to 3, "empty" to null))
                                    call.respondText("ok")
                                }
                                get("/boom") { error("kaputt") }
                            }
                        }
                    }
                    listOf("/dishes", "/boom").forEach { path ->
                        client.get(path) {
                            header("X-PostHog-Distinct-Id", "anon-123")
                            header("X-PostHog-Session-Id", "session-9")
                        }
                    }
                }

                analytics.stop()

                eventually(10.seconds) {
                    posthog.events().mapNotNull { it.string("event") } shouldContainAll
                        listOf("dish_list_viewed", "\$exception")
                }
                val viewed = posthog.events().first { it.string("event") == "dish_list_viewed" }
                viewed.string("distinct_id") shouldBe "anon-123"
                viewed.property("\$session_id") shouldBe "session-9"
                viewed.property("count") shouldBe "3"
                viewed["properties"]!!.jsonObject.containsKey("empty") shouldBe false
                val exception = posthog.events().first { it.string("event") == "\$exception" }
                exception.string("distinct_id") shouldBe "anon-123"
                exception.property("http_path") shouldBe "/boom"
                exception.property("\$session_id") shouldBe "session-9"
            }
        }

        test("an explicit distinct id wins over the request's, and without any the event is dropped") {
            FakePostHog().use { posthog ->
                val analytics = PostHogAnalytics(PostHogSettings(projectToken = "phc_test", host = posthog.url))

                analytics.capture("order_placed", mapOf("total" to 12.5), distinctId = "user-7")
                analytics.capture("nobody_knows_me")
                analytics.stop()

                eventually(10.seconds) { posthog.events().mapNotNull { it.string("event") } shouldBe listOf("order_placed") }
                posthog.events().single().string("distinct_id") shouldBe "user-7"
            }
        }

        test("without a project token analytics is off") {
            val analytics = PostHogAnalytics(PostHogSettings(projectToken = null))

            analytics.capture("anything", distinctId = "user-1")
            analytics.isFeatureEnabled("new-checkout", "user-1", default = true) shouldBe true
            analytics.featureFlag("new-checkout", "user-1") shouldBe null
            analytics.stop()
        }

        test("erasing a person deletes person, events and recordings through the private API") {
            FakePostHog().use { posthog ->
                val analytics = PostHogAnalytics(PostHogSettings(personalApiKey = Secret("phx_test"), apiHost = posthog.url))

                analytics.erasePerson("user-\"1") shouldBe ErasureResult.ERASED

                val call = posthog.apiCalls().single()
                call.path shouldBe "/api/projects/@current/persons/bulk_delete/"
                call.authorization shouldBe "Bearer phx_test"
                call.body["distinct_ids"]!!
                    .jsonArray
                    .single()
                    .jsonPrimitive.content shouldBe "user-\"1"
                call.body["delete_events"]!!.jsonPrimitive.content shouldBe "true"
                call.body["delete_recordings"]!!.jsonPrimitive.content shouldBe "true"
            }
        }

        test("without a personal API key nothing is erased; a failing API throws so callers retry") {
            PostHogAnalytics(PostHogSettings(projectToken = "phc_test")).erasePerson("user-1") shouldBe ErasureResult.NOT_CONFIGURED

            FakePostHog().use { posthog ->
                posthog.apiStatus = HttpStatusCode.InternalServerError
                val analytics = PostHogAnalytics(PostHogSettings(personalApiKey = Secret("phx_test"), apiHost = posthog.url))

                shouldThrow<IllegalStateException> { analytics.erasePerson("user-1") }
            }
        }
    })
