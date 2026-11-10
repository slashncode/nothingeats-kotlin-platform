package io.github.slashncode.nothingeats.platform.posthog

import io.github.slashncode.nothingeats.platform.core.analytics.AnalyticsContext
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.http.HttpExtension
import io.github.slashncode.nothingeats.platform.http.clientAddress
import io.github.slashncode.posthog.ktor.MDC_DISTINCT_ID
import io.github.slashncode.posthog.ktor.MDC_SESSION_ID
import io.github.slashncode.posthog.ktor.PostHog
import io.github.slashncode.posthog.ktor.posthog
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.path
import kotlinx.coroutines.withContext

/**
 * posthog-ktor's plugin with the service's [PostHogAnalytics.client]:
 *
 * - every call runs with the browser's distinct id and session id (posthog-js' tracing headers),
 *   which use cases read as [AnalyticsContext]; events captured during the call carry its request
 *   properties (`$current_url`, `$route`, …) and `request_id`;
 * - the call's log lines, its request line included, carry them under the names PostHog Logs links
 *   by: `posthogDistinctId` to the person, `sessionId` to the session replay;
 * - unexpected failures (a 5xx that is no [AppError]) go to error tracking, attributed to the
 *   caller's person and session.
 *
 * The browser sends its ids only after the visitor agreed to analytics, so without consent the
 * calls, events and log lines carry neither, and an error report carries no address.
 */
class PostHogHttpExtension(
    private val analytics: PostHogAnalytics,
) : HttpExtension {
    override fun Application.install() {
        install(PostHog) {
            client = analytics.client
            // Probes are not visitors.
            requestFilter { !it.request.path().startsWith("/health") }
            // Answered on purpose (404, 409, 422, 429, 503…), not failures worth a report.
            excludedExceptions += AppError::class
            // The address the gateway vouches for, not the gateway's own, and only with consent.
            captureIp = false
            extraProperties { call ->
                mapOf(
                    "\$ip" to call.clientAddress.takeIf { call.posthog.distinctId != null },
                    "request_id" to call.callId,
                )
            }
        }
        // Runs inside the plugin's call context, installed just before it in the same phase.
        intercept(ApplicationCallPipeline.Plugins) {
            withContext(AnalyticsContext(call.posthog.distinctId, call.posthog.sessionId)) { proceed() }
        }
    }

    // CallLogging writes the request line outside the call's coroutine, where the plugin's MDC does not reach.
    override fun callMdc(): Map<String, (ApplicationCall) -> String?> =
        mapOf(
            MDC_DISTINCT_ID to { call -> call.posthog.distinctId },
            MDC_SESSION_ID to { call -> call.posthog.sessionId },
        )
}
