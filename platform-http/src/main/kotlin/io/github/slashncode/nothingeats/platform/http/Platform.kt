package io.github.slashncode.nothingeats.platform.http

import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.http.auth.JwtAuth
import io.github.slashncode.nothingeats.platform.http.auth.public
import io.github.slashncode.nothingeats.platform.http.auth.verifyRouteGuards
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.path
import io.ktor.server.routing.RoutingRoot
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
 * logging MDC as `request_id`), access logs without probe noise, RFC 9457 errors, the
 * `/health/live` and `/health/ready` probes, and JWT authentication when [auth] is given.
 * Once the application has started, every route must be guarded (see `public`/`authenticated`).
 */
fun Application.installPlatform(
    readiness: Readiness,
    healthChecks: HealthChecks,
    auth: JwtAuth? = null,
    exceptionReporter: ExceptionReporter? = null,
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
    installProblemDetails(exceptionReporter)
    auth?.install(this)
    routing {
        public { healthRoutes(readiness, healthChecks) }
    }
    monitor.subscribe(ApplicationStarted) { application ->
        application.pluginOrNull(RoutingRoot)?.let(::verifyRouteGuards)
    }
}
