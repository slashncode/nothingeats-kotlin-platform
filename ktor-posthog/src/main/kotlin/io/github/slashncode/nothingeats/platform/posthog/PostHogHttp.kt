package io.github.slashncode.nothingeats.platform.posthog

import com.posthog.server.PostHogRequestContext
import io.github.slashncode.nothingeats.platform.core.analytics.AnalyticsContext
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.http.ExceptionReporter
import io.github.slashncode.nothingeats.platform.http.HttpExtension
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import kotlinx.coroutines.withContext

private const val MAX_HEADER_LENGTH = 1000

/** Reads the ids that posthog-js sends with tracing headers (`X-PostHog-Distinct-Id`, `X-PostHog-Session-Id`). */
fun ApplicationCall.analyticsContext(): AnalyticsContext =
    AnalyticsContext(
        distinctId = request.headers[PostHogRequestContext.DISTINCT_ID_HEADER].sanitized(),
        sessionId = request.headers[PostHogRequestContext.SESSION_ID_HEADER].sanitized(),
    )

private fun String?.sanitized(): String? = this?.trim()?.take(MAX_HEADER_LENGTH)?.takeIf { it.isNotEmpty() }

/** Makes the browser's PostHog ids available to everything the request runs, via [AnalyticsContext]. */
class PostHogHttpExtension : HttpExtension {
    override fun Application.install() {
        intercept(ApplicationCallPipeline.Plugins) {
            withContext(call.analyticsContext()) { proceed() }
        }
    }
}

/** Sends unexpected exceptions to PostHog error tracking, attributed to the caller's person and session. */
class PostHogExceptionReporter(
    private val analytics: ProductAnalytics,
) : ExceptionReporter {
    override suspend fun report(
        call: ApplicationCall,
        error: Throwable,
    ) {
        // Status pages run outside the request's coroutine context, so read the headers again.
        withContext(call.analyticsContext()) {
            analytics.captureException(
                error,
                mapOf(
                    "http_method" to call.request.httpMethod.value,
                    "http_path" to call.request.path(),
                    "request_id" to call.callId,
                ),
            )
        }
    }
}
