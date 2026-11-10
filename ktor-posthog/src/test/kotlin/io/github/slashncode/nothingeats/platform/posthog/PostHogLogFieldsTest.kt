package io.github.slashncode.nothingeats.platform.posthog

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.posthog.server.PostHogRequestContext
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.http.auth.public
import io.github.slashncode.nothingeats.platform.http.installPlatform
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory

class PostHogLogFieldsTest :
    FunSpec({
        /** Every log event of one request to a route that logs a line itself. */
        fun logsOf(vararg headers: Pair<String, String>): List<ILoggingEvent> {
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            root.addAppender(appender)
            try {
                testApplication {
                    application {
                        val posthog = PostHogHttpExtension(PostHogAnalytics(PostHogSettings()))
                        installPlatform(Readiness(), HealthChecks(emptySet()), callMdc = posthog.callMdc())
                        with(posthog) { install() }
                        routing {
                            public {
                                get("/dishes") {
                                    LoggerFactory.getLogger("dishes").info("Listing dishes")
                                    call.respondText("[]")
                                }
                            }
                        }
                    }
                    client.get("/dishes") { headers.forEach { (name, value) -> header(name, value) } }
                }
            } finally {
                root.detachAppender(appender)
            }
            return appender.list.toList()
        }

        test("with consent, the service's lines and the request line carry the ids PostHog Logs links by") {
            val events =
                logsOf(
                    PostHogRequestContext.DISTINCT_ID_HEADER to "person-1",
                    PostHogRequestContext.SESSION_ID_HEADER to "session-1",
                )

            val ours = events.filter { it.formattedMessage == "Listing dishes" || it.formattedMessage.startsWith("200 OK") }
            ours.shouldNotBeEmpty()
            ours.size shouldBe 2
            ours.forEach { it.mdcPropertyMap shouldContainAll mapOf("posthogDistinctId" to "person-1", "sessionId" to "session-1") }
        }

        test("without consent the browser sends no ids, and the lines carry none") {
            val ours = logsOf().filter { it.formattedMessage == "Listing dishes" }

            ours.shouldNotBeEmpty()
            ours.forEach {
                it.mdcPropertyMap shouldNotContainKey "posthogDistinctId"
                it.mdcPropertyMap shouldNotContainKey "sessionId"
            }
        }
    })
