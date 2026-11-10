package io.github.slashncode.nothingeats.platform.posthog

import com.sksamuel.hoplite.Secret
import io.github.slashncode.nothingeats.platform.core.analytics.AnalyticsContext
import io.github.slashncode.nothingeats.platform.core.analytics.ErasureResult
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import io.github.slashncode.posthog.ktor.POSTHOG_EU_HOST
import io.github.slashncode.posthog.ktor.PostHogClient
import io.github.slashncode.posthog.ktor.PostHogFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class PostHogSettings(
    /** Project API key (public). Null switches analytics off, e.g. for local runs without a project. */
    val projectToken: String? = null,
    val host: String = POSTHOG_EU_HOST,
    /**
     * Personal API key (scopes `feature_flag:read`, `person:write`); when set, persons can be erased,
     * and feature flags are evaluated locally unless [flagsSecretKey] is set.
     */
    val personalApiKey: Secret? = null,
    /**
     * The project's Feature Flags Secure API Key; when set, feature flags are evaluated locally
     * instead of per request. It can read flag definitions and nothing else.
     */
    val flagsSecretKey: Secret? = null,
    /** The private API (app host, not the ingestion host), for erasing persons. */
    val apiHost: String = "https://eu.posthog.com",
    /** `@current` is the project the personal API key belongs to. */
    val projectId: String = "@current",
    /** Flags the browser may see, for a service that serves them (posthog-ktor's `posthogFlags`), e.g. `POSTHOG__CLIENT_FLAGS=a,b`. */
    val clientFlags: List<String> = emptyList(),
)

/**
 * [ProductAnalytics] on posthog-ktor's [client], which [PostHogHttpExtension] shares with the HTTP
 * server: events captured during a call carry its request properties and session, and background
 * work (consumers, cron tasks) captures through the same client without a server.
 *
 * Events default to the browser's distinct id and carry its `$session_id` (both from
 * [AnalyticsContext]). The browser sends them only after the visitor agreed to analytics, so an
 * event without a distinct id is dropped. Flushed and closed last on shutdown.
 */
class PostHogAnalytics(
    private val settings: PostHogSettings,
) : ProductAnalytics,
    LifecycleComponent {
    override val name = "posthog"
    override val order = LifecycleOrder.INFRASTRUCTURE

    /** The posthog-ktor client; off (every call a no-op) without a project token. */
    val client: PostHogClient =
        PostHogClient {
            projectToken = settings.projectToken?.takeIf { it.isNotBlank() }
            host = settings.host
            // Locally, with the flags key or else the personal API key; otherwise one request per evaluation.
            secretKey = (settings.flagsSecretKey ?: settings.personalApiKey)?.value
            captureWithoutDistinctId = false
        }

    override suspend fun stop() {
        withContext(Dispatchers.IO) { client.close() }
    }

    override suspend fun capture(
        event: String,
        properties: Map<String, Any?>,
        distinctId: String?,
    ) {
        val context = AnalyticsContext.current()
        val id = distinctId ?: context?.distinctId ?: return
        client.capture(event, properties.withSession(context), id)
    }

    override suspend fun isFeatureEnabled(
        flag: String,
        distinctId: String,
        default: Boolean,
    ): Boolean {
        val flags = evaluate(flag, distinctId)
        return if (flag in flags.keys) flags.isEnabled(flag) else default
    }

    override suspend fun featureFlag(
        flag: String,
        distinctId: String,
    ): Any? = evaluate(flag, distinctId).getFlag(flag)

    // Reading the value records the exposure ($feature_flag_called) that experiments count; the payload alone would not.
    override suspend fun featureFlagPayload(
        flag: String,
        distinctId: String,
    ): String? {
        val flags = evaluate(flag, distinctId)
        if (flags.getFlag(flag) == null) return null
        return flags.getFlagPayload(flag)
    }

    private suspend fun evaluate(
        flag: String,
        distinctId: String,
    ): PostHogFlags = client.evaluateFlags(distinctId) { flagKeys(listOf(flag)) }

    override suspend fun captureException(
        error: Throwable,
        properties: Map<String, Any?>,
        distinctId: String?,
    ) {
        val context = AnalyticsContext.current()
        client.captureException(error, properties.withSession(context), distinctId ?: context?.distinctId)
    }

    // Person, events and recordings; PostHog deletes events in a weekly batch and answers 202 at once.
    override suspend fun erasePerson(distinctId: String): ErasureResult {
        val key = settings.personalApiKey ?: return ErasureResult.NOT_CONFIGURED
        val request =
            HttpRequest
                .newBuilder(URI("${settings.apiHost.trimEnd('/')}/api/projects/${settings.projectId}/persons/bulk_delete/"))
                .timeout(Duration.ofSeconds(ERASURE_TIMEOUT_SECONDS))
                .header("Authorization", "Bearer ${key.value}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(erasureBody(distinctId)))
                .build()
        val response = withContext(Dispatchers.IO) { http.send(request, HttpResponse.BodyHandlers.ofString()) }
        check(response.statusCode() in 200..299) { "PostHog person erasure failed with HTTP ${response.statusCode()}" }
        return ErasureResult.ERASED
    }

    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(ERASURE_TIMEOUT_SECONDS)).build() }

    private fun erasureBody(distinctId: String): String {
        val escaped = distinctId.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"distinct_ids":["$escaped"],"delete_events":true,"delete_recordings":true}"""
    }

    // Inside a call the client adds the call's session itself; work outside a call names it in AnalyticsContext.
    private fun Map<String, Any?>.withSession(context: AnalyticsContext?): Map<String, Any?> {
        val sessionId = context?.sessionId ?: return this
        return if (containsKey(SESSION_ID)) this else this + (SESSION_ID to sessionId)
    }

    private companion object {
        const val SESSION_ID = "\$session_id"
        const val ERASURE_TIMEOUT_SECONDS = 10L
    }
}
