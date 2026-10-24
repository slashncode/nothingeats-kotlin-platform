package io.github.slashncode.nothingeats.platform.core.analytics

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Who is behind the current request, as the browser's analytics SDK sees them: its distinct id
 * and session id. Server events carrying the session id link to the session replay.
 */
data class AnalyticsContext(
    val distinctId: String?,
    val sessionId: String?,
) : AbstractCoroutineContextElement(AnalyticsContext) {
    companion object Key : CoroutineContext.Key<AnalyticsContext> {
        suspend fun current(): AnalyticsContext? = currentCoroutineContext()[Key]
    }
}

/**
 * Port for product analytics, so use cases record events and read feature flags without knowing
 * the vendor. Event names are snake_case `object_verb`, e.g. `user_signed_up`.
 */
interface ProductAnalytics {
    /** [distinctId] defaults to the one from [AnalyticsContext]; without any, the event is dropped. */
    suspend fun capture(
        event: String,
        properties: Map<String, Any?> = emptyMap(),
        distinctId: String? = null,
    )

    suspend fun isFeatureEnabled(
        flag: String,
        distinctId: String,
        default: Boolean = false,
    ): Boolean

    /** The flag's variant (String), on/off (Boolean), or null when unknown. */
    suspend fun featureFlag(
        flag: String,
        distinctId: String,
    ): Any?

    suspend fun captureException(
        error: Throwable,
        properties: Map<String, Any?> = emptyMap(),
        distinctId: String? = null,
    )
}

/** For services or environments without analytics. */
object NoopAnalytics : ProductAnalytics {
    override suspend fun capture(
        event: String,
        properties: Map<String, Any?>,
        distinctId: String?,
    ) = Unit

    override suspend fun isFeatureEnabled(
        flag: String,
        distinctId: String,
        default: Boolean,
    ) = default

    override suspend fun featureFlag(
        flag: String,
        distinctId: String,
    ): Any? = null

    override suspend fun captureException(
        error: Throwable,
        properties: Map<String, Any?>,
        distinctId: String?,
    ) = Unit
}
