package io.github.slashncode.nothingeats.platform.testing

import io.github.slashncode.nothingeats.platform.core.analytics.AnalyticsContext
import io.github.slashncode.nothingeats.platform.core.analytics.ErasureResult
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Records events and serves feature flags from a map, resolving distinct ids like the real adapter. */
class RecordingAnalytics : ProductAnalytics {
    data class Event(
        val name: String,
        val distinctId: String,
        val properties: Map<String, Any?>,
    )

    val events = CopyOnWriteArrayList<Event>()
    val exceptions = CopyOnWriteArrayList<Throwable>()
    val flags = ConcurrentHashMap<String, Any>()

    /** JSON payloads by flag key, for [featureFlagPayload]. */
    val payloads = ConcurrentHashMap<String, String>()
    val erased = CopyOnWriteArrayList<String>()

    /** What [erasePerson] answers; set to NOT_CONFIGURED or make it throw to test retries. */
    var erasure: () -> ErasureResult = { ErasureResult.ERASED }

    override suspend fun capture(
        event: String,
        properties: Map<String, Any?>,
        distinctId: String?,
    ) {
        val context = AnalyticsContext.current()
        val id = distinctId ?: context?.distinctId ?: return
        events += Event(event, id, properties + listOfNotNull(context?.sessionId?.let { "\$session_id" to it }))
    }

    override suspend fun isFeatureEnabled(
        flag: String,
        distinctId: String,
        default: Boolean,
    ) = flags[flag] as? Boolean ?: default

    override suspend fun featureFlag(
        flag: String,
        distinctId: String,
    ): Any? = flags[flag]

    override suspend fun featureFlagPayload(
        flag: String,
        distinctId: String,
    ): String? = payloads[flag]

    override suspend fun captureException(
        error: Throwable,
        properties: Map<String, Any?>,
        distinctId: String?,
    ) {
        exceptions += error
    }

    override suspend fun erasePerson(distinctId: String): ErasureResult =
        erasure().also { if (it == ErasureResult.ERASED) erased += distinctId }
}
