package io.github.slashncode.nothingeats.platform.posthog

import io.github.slashncode.nothingeats.platform.core.analytics.AnalyticsContext
import io.github.slashncode.nothingeats.platform.core.analytics.FlagValue
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.http.PlatformJson
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `GET flags`: the values of [flags] for the browser's distinct id, so the web app can bootstrap
 * posthog-js and render the right variant from the first paint instead of flipping once PostHog
 * has answered. With a flags key the service evaluates them locally, so nothing about the visitor
 * leaves the cluster for this, and no exposure is recorded: the browser records it when it shows a
 * variant. The browser sends its distinct id only after the visitor agreed to analytics, so without
 * consent the answer is empty and everyone sees the default.
 *
 * Answers `{"flags": {"<key>": true | "<variant>"}, "payloads": {"<key>": <json>}}`; only [flags]
 * are evaluated, so server-side flags and their payloads stay on the server.
 */
fun Route.clientFlags(
    analytics: ProductAnalytics,
    flags: List<String>,
) {
    get("/flags") {
        val distinctId = AnalyticsContext.current()?.distinctId
        val values = if (distinctId == null || flags.isEmpty()) emptyMap() else analytics.flagsForClient(flags, distinctId)
        call.response.cacheControl(CacheControl.NoStore(null))
        call.respondText(PlatformJson.encodeToString(JsonObject.serializer(), values.toJson()), ContentType.Application.Json)
    }
}

private fun Map<String, FlagValue>.toJson() =
    JsonObject(
        mapOf(
            "flags" to JsonObject(mapValues { (_, flag) -> flag.value.toJson() }),
            "payloads" to JsonObject(mapNotNull { (key, flag) -> flag.payload?.let { key to it.parsed() } }.toMap()),
        ),
    )

private fun Any.toJson(): JsonElement = if (this is Boolean) JsonPrimitive(this) else JsonPrimitive(toString())

// A payload is JSON in PostHog; one that is not goes out as a string rather than failing the request.
private fun String.parsed(): JsonElement = runCatching { PlatformJson.parseToJsonElement(this) }.getOrElse { JsonPrimitive(this) }
