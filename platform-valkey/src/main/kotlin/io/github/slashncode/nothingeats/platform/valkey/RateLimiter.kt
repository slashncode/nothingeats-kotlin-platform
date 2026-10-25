package io.github.slashncode.nothingeats.platform.valkey

import io.lettuce.core.ScriptOutputType
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** At most [limit] attempts per [window] and subject. [name] becomes part of the key. */
data class RateLimit(
    val name: String,
    val limit: Int,
    val window: Duration,
) {
    init {
        require(limit > 0) { "limit must be positive" }
        require(window.isPositive()) { "window must be positive" }
        require(KEY_PART.matches(name)) { "name must be lowercase letters, digits and dashes" }
    }

    private companion object {
        val KEY_PART = Regex("[a-z0-9-]+")
    }
}

sealed interface RateDecision {
    data object Allowed : RateDecision

    data class Limited(
        val retryAfter: Duration,
    ) : RateDecision
}

/**
 * Fixed-window counters in Valkey, one key per limit, subject and window, expiring with the window.
 *
 * Subjects (IP addresses, email addresses) are hashed, so no personal data ends up in key names.
 * Fails open: if Valkey cannot be reached, the attempt is allowed and a warning is logged. An outage
 * of the limiter must not lock everyone out; what it protects has its own cost (e.g. Argon2).
 */
class RateLimiter(
    private val valkey: Valkey,
    /** Namespace of the service, e.g. `identity`. */
    private val prefix: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(RateLimiter::class.java)

    /** Counts the attempt and decides whether it is within the limit. */
    suspend fun attempt(
        limit: RateLimit,
        subject: String,
    ): RateDecision =
        failOpen(limit) {
            val window = window(limit)
            val count =
                valkey
                    .commands()
                    .eval<Long>(
                        INCREMENT,
                        ScriptOutputType.INTEGER,
                        arrayOf(window.key(limit, subject)),
                        window.ttlMillis.toString(),
                    ).await()
            decide(limit, count, window)
        }

    /**
     * Decides without counting: check before an expensive operation, then [attempt] only when it
     * failed, so that only failures count (e.g. wrong passwords per account).
     */
    suspend fun peek(
        limit: RateLimit,
        subject: String,
    ): RateDecision =
        failOpen(limit) {
            val window = window(limit)
            val count =
                valkey
                    .commands()
                    .get(window.key(limit, subject))
                    .await()
                    ?.toLongOrNull() ?: 0
            decide(limit, count, window, counted = false)
        }

    private fun decide(
        limit: RateLimit,
        count: Long,
        window: Window,
        counted: Boolean = true,
    ): RateDecision {
        val exceeded = if (counted) count > limit.limit else count >= limit.limit
        return if (exceeded) RateDecision.Limited(window.remainingMillis.milliseconds) else RateDecision.Allowed
    }

    private suspend fun failOpen(
        limit: RateLimit,
        block: suspend () -> RateDecision,
    ): RateDecision =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Rate limit {} not checked, Valkey unavailable; allowing: {}", limit.name, e.message)
            RateDecision.Allowed
        }

    private fun window(limit: RateLimit): Window {
        val now = clock.millis()
        val length = limit.window.inWholeMilliseconds
        val index = now / length
        return Window(index, remainingMillis = (index + 1) * length - now, ttlMillis = length)
    }

    private inner class Window(
        val index: Long,
        val remainingMillis: Long,
        val ttlMillis: Long,
    ) {
        fun key(
            limit: RateLimit,
            subject: String,
        ) = "$prefix:rate:${limit.name}:${hash(subject)}:$index"
    }

    private companion object {
        /** INCR and, for the first hit of a window, the expiry, atomically. */
        const val INCREMENT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return count
        """

        @OptIn(ExperimentalStdlibApi::class)
        fun hash(subject: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(subject.toByteArray())
                .toHexString()
                .take(32)
    }
}
