package io.github.slashncode.nothingeats.platform.http

import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.path
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.util.UUID

/** JSON settings shared by every NothingEats HTTP API. */
val PlatformJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

private const val MAX_REQUEST_ID_LENGTH = 128

/**
 * Installs what every service needs: JSON, request ids (`X-Request-Id`, echoed and put in the
 * logging MDC as `request_id`), access logs without probe noise, RFC 9457 errors, and the
 * `/health/live` and `/health/ready` probes.
 */
fun Application.installPlatform(
    readiness: Readiness,
    healthChecks: HealthChecks,
) {
    install(ContentNegotiation) {
        json(PlatformJson)
    }
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() && it.length <= MAX_REQUEST_ID_LENGTH }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        // Plain text: ANSI colour codes would end up inside the JSON log lines.
        disableDefaultColors()
        callIdMdc("request_id")
        filter { !it.request.path().startsWith("/health") }
    }
    installProblemDetails()
    routing {
        healthRoutes(readiness, healthChecks)
    }
}
