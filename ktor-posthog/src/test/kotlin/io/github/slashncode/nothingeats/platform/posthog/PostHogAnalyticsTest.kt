package io.github.slashncode.nothingeats.platform.posthog

import com.sksamuel.hoplite.Secret
import io.github.slashncode.nothingeats.platform.core.analytics.AnalyticsContext
import io.github.slashncode.nothingeats.platform.core.analytics.ErasureResult
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.http.auth.public
import io.github.slashncode.nothingeats.platform.http.installPlatform
import io.github.slashncode.posthog.ktor.DISTINCT_ID_HEADER
import io.github.slashncode.posthog.ktor.SESSION_ID_HEADER
import io.github.slashncode.posthog.ktor.test.PostHogFake
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

private fun PostHogFake.analytics(flagsSecretKey: Secret? = null) =
    PostHogAnalytics(PostHogSettings(projectToken = projectToken, host = host, flagsSecretKey = flagsSecretKey))

/** A service with the platform's HTTP setup and PostHog, whose routes capture through [analytics]. */
private fun serviceTest(
    analytics: PostHogAnalytics,
    requests: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application {
        val posthog = PostHogHttpExtension(analytics)
        installPlatform(Readiness(), HealthChecks(emptySet()), callMdc = posthog.callMdc())
        with(posthog) { install() }
        routing {
            public {
                get("/dishes/{id}") {
                    analytics.capture("dish_viewed", mapOf("count" to 3, "empty" to null))
                    call.respondText("ok")
                }
                get("/boom") { error("broken") }
                get("/gone") { throw AppError.NotFound(ErrorCode("DISH_NOT_FOUND"), "No such dish") }
                get("/busy") { throw AppError.Unavailable(ErrorCode("SEARCH_UNAVAILABLE"), "Try again") }
            }
        }
    }
    requests()
}

class PostHogAnalyticsTest :
    FunSpec({
        test("server events carry the browser's distinct id, session, request and address; failures go to error tracking") {
            PostHogFake().use { fake ->
                val analytics = fake.analytics()
                serviceTest(analytics) {
                    listOf("/dishes/7", "/boom", "/gone", "/busy").forEach { path ->
                        client.get(path) {
                            header(DISTINCT_ID_HEADER, "anon-123")
                            header(SESSION_ID_HEADER, "session-9")
                            header(HttpHeaders.XForwardedFor, "198.51.100.1, 203.0.113.7")
                            header(HttpHeaders.XRequestId, "request-1")
                        }
                    }
                    client.get("/health/live") { header(DISTINCT_ID_HEADER, "anon-123") }.status shouldBe HttpStatusCode.OK
                }
                analytics.stop()

                fake.assertCaptured("dish_viewed") {
                    distinctId shouldBe "anon-123"
                    property("count", 3)
                    property("\$session_id", "session-9")
                    property("\$route", "/dishes/{id}")
                    property("\$request_path", "/dishes/7")
                    property("\$ip", "203.0.113.7")
                    property("request_id", "request-1")
                    properties.containsKey("empty") shouldBe false
                }
                val exception =
                    fake.assertCaptured("\$exception") {
                        distinctId shouldBe "anon-123"
                        property("\$request_path", "/boom")
                        property("\$session_id", "session-9")
                        property("\$ip", "203.0.113.7")
                    }
                exception.properties["\$exception_list"]!!
                    .jsonArray
                    .toString()
                    .contains("broken") shouldBe true
                // Errors the service answers on purpose (404, 503) are no reports; probes are no visitors.
                fake.captured("\$exception").size shouldBe 1
                fake.events.none { it.property("\$request_path") == "/health/live" } shouldBe true
            }
        }

        test("without consent the browser sends no ids: events are dropped, a failure is reported without an address") {
            PostHogFake().use { fake ->
                val analytics = fake.analytics()
                serviceTest(analytics) {
                    client.get("/dishes/7") { header(HttpHeaders.XForwardedFor, "203.0.113.7") }
                    client.get("/boom") { header(HttpHeaders.XForwardedFor, "203.0.113.7") }
                }
                analytics.stop()

                fake.assertCaptured("\$exception") {
                    property("\$request_path", "/boom")
                    property("\$ip", null)
                }
                fake.assertNotCaptured("dish_viewed")
            }
        }

        test("outside a call: an explicit distinct id, else the AnalyticsContext's; without any the event is dropped") {
            PostHogFake().use { fake ->
                val analytics = fake.analytics()

                analytics.capture("order_placed", mapOf("total" to 12.5), distinctId = "user-7")
                analytics.capture("nobody_knows_me")
                withContext(AnalyticsContext("user-8", "session-2")) {
                    analytics.capture("order_delivered")
                    analytics.captureException(IllegalStateException("consumer failed"))
                }
                analytics.stop()

                fake.assertCaptured("order_placed") {
                    distinctId shouldBe "user-7"
                    property("total", 12.5)
                    // No visitor's address outside a call: PostHog would locate the server.
                    property("\$geoip_disable", true)
                }
                fake.assertCaptured("order_delivered") {
                    distinctId shouldBe "user-8"
                    property("\$session_id", "session-2")
                }
                fake.assertCaptured("\$exception") { distinctId shouldBe "user-8" }
                fake.assertNotCaptured("nobody_knows_me")
            }
        }

        test("without a project token analytics is off") {
            val analytics = PostHogAnalytics(PostHogSettings(projectToken = null))

            analytics.client.isEnabled shouldBe false
            analytics.capture("anything", distinctId = "user-1")
            analytics.isFeatureEnabled("new-checkout", "user-1", default = true) shouldBe true
            analytics.featureFlag("new-checkout", "user-1") shouldBe null
            analytics.featureFlagPayload("new-checkout", "user-1") shouldBe null
            analytics.stop()
        }

        test("with the flags key, flags are evaluated locally and the exposure is still recorded") {
            PostHogFake().use { fake ->
                fake.flagDefinitions =
                    """
                    {"flags":[{"id":1,"key":"spin-wheel-odds","active":true,"deleted":false,"ensure_experience_continuity":false,
                      "filters":{"groups":[{"properties":[],"rollout_percentage":100}],
                        "multivariate":{"variants":[{"key":"control","rollout_percentage":0},{"key":"generous","rollout_percentage":100}]},
                        "payloads":{"generous":"{\"weights\":[1,2]}"}}}],
                     "group_type_mapping":{},"cohorts":{}}
                    """.trimIndent()
                val analytics = fake.analytics(flagsSecretKey = Secret("phs_test"))

                eventually(10.seconds) { analytics.featureFlag("spin-wheel-odds", "user-1") shouldBe "generous" }
                analytics.featureFlagPayload("spin-wheel-odds", "user-1") shouldBe """{"weights":[1,2]}"""
                analytics.isFeatureEnabled("spin-wheel-odds", "user-1") shouldBe true
                analytics.isFeatureEnabled("unknown", "user-1", default = true) shouldBe true
                analytics.stop()

                fake.assertCaptured("\$feature_flag_called") {
                    distinctId shouldBe "user-1"
                    property("\$feature_flag_response", "generous")
                    property("locally_evaluated", true)
                }
            }
        }

        test("erasing a person deletes person, events and recordings through the private API") {
            PrivateApiFake().use { api ->
                val analytics = PostHogAnalytics(PostHogSettings(personalApiKey = Secret("phx_test"), apiHost = api.url))

                analytics.erasePerson("user-\"1") shouldBe ErasureResult.ERASED

                val call = api.calls().single()
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

            PrivateApiFake().use { api ->
                api.status = HttpStatusCode.InternalServerError
                val analytics = PostHogAnalytics(PostHogSettings(personalApiKey = Secret("phx_test"), apiHost = api.url))

                shouldThrow<IllegalStateException> { analytics.erasePerson("user-1") }.message shouldNotBe null
            }
        }
    })
