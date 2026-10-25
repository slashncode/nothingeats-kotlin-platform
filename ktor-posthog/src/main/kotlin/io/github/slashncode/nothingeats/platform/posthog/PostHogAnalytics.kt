package io.github.slashncode.nothingeats.platform.posthog

import com.posthog.server.PostHog
import com.posthog.server.PostHogConfig
import com.posthog.server.PostHogInterface
import com.sksamuel.hoplite.Secret
import io.github.slashncode.nothingeats.platform.core.analytics.AnalyticsContext
import io.github.slashncode.nothingeats.platform.core.analytics.ErasureResult
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
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
    val host: String = "https://eu.i.posthog.com",
    /**
     * Personal API key (scopes `feature_flag:read`, `person:write`); when set, feature flags are
     * evaluated locally instead of per request, and persons can be erased.
     */
    val personalApiKey: Secret? = null,
    /** The private API (app host, not the ingestion host), for erasing persons. */
    val apiHost: String = "https://eu.posthog.com",
    /** `@current` is the project the personal API key belongs to. */
    val projectId: String = "@current",
)

/**
 * [ProductAnalytics] backed by the official PostHog server SDK.
 *
 * Events default to the browser's distinct id and carry its `$session_id` (both from
 * [AnalyticsContext]), so server-side events show up in the same person and session replay as the
 * client-side ones. Flushed and closed last on shutdown.
 */
class PostHogAnalytics(
    private val settings: PostHogSettings,
) : ProductAnalytics,
    LifecycleComponent {
    override val name = "posthog"
    override val order = LifecycleOrder.INFRASTRUCTURE

    private val client: PostHogInterface? =
        settings.projectToken?.takeIf { it.isNotBlank() }?.let { token ->
            PostHog.with(
                PostHogConfig(
                    projectToken = token,
                    host = settings.host,
                    personalApiKey = settings.personalApiKey?.value,
                    localEvaluation = settings.personalApiKey != null,
                ),
            )
        }

    override suspend fun stop() {
        withContext(Dispatchers.IO) { client?.close() }
    }

    override suspend fun capture(
        event: String,
        properties: Map<String, Any?>,
        distinctId: String?,
    ) {
        val client = client ?: return
        val context = AnalyticsContext.current()
        val id = distinctId ?: context?.distinctId ?: return
        client.capture(distinctId = id, event = event, properties = enrich(properties, context))
    }

    override suspend fun isFeatureEnabled(
        flag: String,
        distinctId: String,
        default: Boolean,
    ): Boolean {
        val flags = evaluate(flag, distinctId) ?: return default
        return if (flag in flags.keys) flags.isEnabled(flag) else default
    }

    override suspend fun featureFlag(
        flag: String,
        distinctId: String,
    ): Any? = evaluate(flag, distinctId)?.getFlag(flag)

    // Locally when a personal API key is configured, otherwise one /flags request per call.
    private suspend fun evaluate(
        flag: String,
        distinctId: String,
    ) = client?.let { withContext(Dispatchers.IO) { it.evaluateFlags(distinctId = distinctId, flagKeys = listOf(flag)) } }

    override suspend fun captureException(
        error: Throwable,
        properties: Map<String, Any?>,
        distinctId: String?,
    ) {
        val client = client ?: return
        val context = AnalyticsContext.current()
        client.captureException(error, distinctId ?: context?.distinctId, enrich(properties, context))
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

    private fun enrich(
        properties: Map<String, Any?>,
        context: AnalyticsContext?,
    ): Map<String, Any> =
        buildMap {
            properties.forEach { (key, value) -> if (value != null) put(key, value) }
            context?.sessionId?.let { putIfAbsent(SESSION_ID, it) }
        }

    private companion object {
        const val SESSION_ID = "\$session_id"
        const val ERASURE_TIMEOUT_SECONDS = 10L
    }
}
