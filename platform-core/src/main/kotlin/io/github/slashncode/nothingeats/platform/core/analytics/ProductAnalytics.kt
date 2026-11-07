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

        /**
         * Whether the current request comes from a browser with analytics consent: the web app
         * loads PostHog, and so sends its distinct id, only after "yes". Events published for
         * other services carry this answer, so their consumers capture only with consent.
         */
        suspend fun consented(): Boolean = current()?.distinctId != null
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

    /**
     * The JSON payload of the variant [distinctId] gets (an experiment's settings, e.g. odds), or
     * null when the flag is off, unknown or has no payload. Counts as an exposure.
     */
    suspend fun featureFlagPayload(
        flag: String,
        distinctId: String,
    ): String?

    /**
     * The values of [flags] that are on for [distinctId], without recording an exposure: for a client
     * (the web app) that renders with them before its own SDK has loaded and records the exposure
     * itself when it shows a variant. Flags that are off or unknown are left out.
     */
    suspend fun flagsForClient(
        flags: Collection<String>,
        distinctId: String,
    ): Map<String, FlagValue> = emptyMap()

    suspend fun captureException(
        error: Throwable,
        properties: Map<String, Any?> = emptyMap(),
        distinctId: String? = null,
    )

    /**
     * Erases everything the vendor holds about [distinctId] (person, events, recordings) for a GDPR
     * deletion. [ErasureResult.NOT_CONFIGURED] when this environment cannot erase (e.g. no API key),
     * so callers keep the request and retry later; throws when the vendor fails.
     */
    suspend fun erasePerson(distinctId: String): ErasureResult
}

enum class ErasureResult { ERASED, NOT_CONFIGURED }

/** A flag that is on: [value] is `true` or the variant's key, [payload] the variant's JSON payload. */
data class FlagValue(
    val value: Any,
    val payload: String? = null,
)

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

    override suspend fun featureFlagPayload(
        flag: String,
        distinctId: String,
    ): String? = null

    override suspend fun captureException(
        error: Throwable,
        properties: Map<String, Any?>,
        distinctId: String?,
    ) = Unit

    override suspend fun erasePerson(distinctId: String) = ErasureResult.NOT_CONFIGURED
}
