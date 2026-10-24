package io.github.slashncode.nothingeats.platform.http

import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val state: String,
    val checks: Map<String, CheckResponse>? = null,
)

@Serializable
data class CheckResponse(
    val status: String,
    val detail: String? = null,
)

private fun status(healthy: Boolean) = if (healthy) "UP" else "DOWN"

/**
 * - `/health/live`: the process should not be restarted (only false once stopped).
 * - `/health/ready`: the instance may receive traffic — lifecycle is READY and every check passes.
 */
internal fun Route.healthRoutes(
    readiness: Readiness,
    healthChecks: HealthChecks,
) {
    get("/health/live") {
        val live = readiness.isLive
        call.respond(
            if (live) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            HealthResponse(status(live), readiness.state.name),
        )
    }
    get("/health/ready") {
        if (!readiness.isReady) {
            call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse("DOWN", readiness.state.name))
            return@get
        }
        val report = healthChecks.run()
        call.respond(
            if (report.healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            HealthResponse(
                status = status(report.healthy),
                state = readiness.state.name,
                checks = report.checks.mapValues { (_, check) -> CheckResponse(status(check.healthy), check.detail) },
            ),
        )
    }
}
