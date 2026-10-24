package io.github.slashncode.nothingeats.platform.http

import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** An RFC 9457 problem response, extended with a stable error [code] and the [requestId]. */
@Serializable
data class Problem(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
    val code: String,
    val requestId: String? = null,
    val violations: List<ProblemViolation>? = null,
)

@Serializable
data class ProblemViolation(
    val field: String,
    val message: String,
)

val ProblemJson = ContentType("application", "problem+json")

private val log = LoggerFactory.getLogger("io.github.slashncode.nothingeats.platform.http.Problems")

internal fun Application.installProblemDetails() {
    install(StatusPages) {
        exception<AppError> { call, error ->
            if (error is AppError.RateLimited) {
                error.retryAfter?.let { call.response.header(HttpHeaders.RetryAfter, it.inWholeSeconds.coerceAtLeast(1)) }
            }
            call.respondProblem(
                status = error.httpStatus(),
                code = error.code.value,
                detail = error.message,
                violations = (error as? AppError.Invalid)?.violations?.map { ProblemViolation(it.field, it.message) },
            )
        }
        exception<BadRequestException> { call, _ ->
            call.respondProblem(HttpStatusCode.BadRequest, "BAD_REQUEST", "The request could not be read")
        }
        exception<Throwable> { call, error ->
            log.error("Unhandled error on {} {}", call.request.local.method.value, call.request.path(), error)
            call.respondProblem(HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "An unexpected error occurred")
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondProblem(status, "NOT_FOUND", "No resource at ${call.request.path()}")
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respondProblem(status, "METHOD_NOT_ALLOWED", "Method not allowed on ${call.request.path()}")
        }
    }
}

suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    code: String,
    detail: String?,
    violations: List<ProblemViolation>? = null,
) {
    val problem =
        Problem(
            title = status.description,
            status = status.value,
            detail = detail,
            instance = request.path(),
            code = code,
            requestId = callId,
            violations = violations?.takeIf { it.isNotEmpty() },
        )
    respondText(PlatformJson.encodeToString(Problem.serializer(), problem), ProblemJson, status)
}

private fun AppError.httpStatus(): HttpStatusCode =
    when (this) {
        is AppError.NotFound -> HttpStatusCode.NotFound
        is AppError.Conflict -> HttpStatusCode.Conflict
        is AppError.Invalid -> HttpStatusCode.UnprocessableEntity
        is AppError.Unauthenticated -> HttpStatusCode.Unauthorized
        is AppError.Forbidden -> HttpStatusCode.Forbidden
        is AppError.RateLimited -> HttpStatusCode.TooManyRequests
        is AppError.Unavailable -> HttpStatusCode.ServiceUnavailable
    }
