package io.github.slashncode.nothingeats.platform.core.lifecycle

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A dependency check that decides whether this instance can serve traffic. */
interface HealthCheck {
    val name: String

    suspend fun check(): HealthStatus
}

data class HealthStatus(
    val healthy: Boolean,
    val detail: String? = null,
) {
    companion object {
        val UP = HealthStatus(healthy = true)

        fun down(detail: String) = HealthStatus(healthy = false, detail = detail)
    }
}

data class HealthReport(
    val checks: Map<String, HealthStatus>,
) {
    val healthy: Boolean get() = checks.values.all { it.healthy }
}

/** Runs all checks concurrently; a check that throws or exceeds [timeout] counts as down. */
class HealthChecks(
    private val checks: Set<HealthCheck>,
    private val timeout: Duration = 2.seconds,
) {
    suspend fun run(): HealthReport =
        coroutineScope {
            checks
                .map { check -> async { check.name to runSafely(check) } }
                .awaitAll()
                .toMap()
                .let(::HealthReport)
        }

    private suspend fun runSafely(check: HealthCheck): HealthStatus =
        try {
            withTimeoutOrNull(timeout) { check.check() } ?: HealthStatus.down("timed out after $timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HealthStatus.down(e::class.simpleName ?: "error")
        }
}
