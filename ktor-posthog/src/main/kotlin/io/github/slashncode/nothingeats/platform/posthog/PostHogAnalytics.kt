package io.github.slashncode.nothingeats.platform.posthog

import com.posthog.server.PostHog
import com.posthog.server.PostHogConfig
import com.posthog.server.PostHogInterface
import com.sksamuel.hoplite.Secret
import io.github.slashncode.nothingeats.platform.core.analytics.AnalyticsContext
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PostHogSettings(
    /** Project API key (public). Null switches analytics off, e.g. for local runs without a project. */
    val projectToken: String? = null,
    val host: String = "https://eu.i.posthog.com",
    /** Personal API key; when set, feature flags are evaluated locally instead of per request. */
    val personalApiKey: Secret? = null,
)

/**
 * [ProductAnalytics] backed by the official PostHog server SDK.
 *
 * Events default to the browser's distinct id and carry its `$session_id` (both from
 * [AnalyticsContext]), so server-side events show up in the same person and session replay as the
 * client-side ones. Flushed and closed last on shutdown.
 */
class PostHogAnalytics(
    settings: PostHogSettings,
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
    }
}
