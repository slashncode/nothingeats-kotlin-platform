package io.github.slashncode.nothingeats.platform.core.error

import kotlin.time.Duration

/** A stable, machine-readable error identifier in `UPPER_SNAKE_CASE`, e.g. `DISH_NOT_FOUND`. */
@JvmInline
value class ErrorCode(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "Error code '$value' must be UPPER_SNAKE_CASE" }
    }

    override fun toString() = value

    private companion object {
        val PATTERN = Regex("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*")
    }
}

data class Violation(
    val field: String,
    val message: String,
)

/**
 * An expected failure. The message is safe to show to clients; adapters map the subtype
 * to an HTTP status or gRPC code. Unexpected exceptions are never shown to clients.
 */
sealed class AppError(
    val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class NotFound(
        code: ErrorCode,
        message: String,
    ) : AppError(code, message)

    class Conflict(
        code: ErrorCode,
        message: String,
        cause: Throwable? = null,
    ) : AppError(code, message, cause)

    class Invalid(
        code: ErrorCode,
        message: String,
        val violations: List<Violation> = emptyList(),
    ) : AppError(code, message)

    class Unauthenticated(
        code: ErrorCode = ErrorCode("UNAUTHENTICATED"),
        message: String = "Authentication required",
    ) : AppError(code, message)

    class Forbidden(
        code: ErrorCode = ErrorCode("FORBIDDEN"),
        message: String = "Not allowed",
    ) : AppError(code, message)

    class RateLimited(
        code: ErrorCode = ErrorCode("RATE_LIMITED"),
        message: String = "Too many requests",
        val retryAfter: Duration? = null,
    ) : AppError(code, message)

    class Unavailable(
        code: ErrorCode,
        message: String,
        cause: Throwable? = null,
    ) : AppError(code, message, cause)
}
