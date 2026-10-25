package io.github.slashncode.nothingeats.platform.valkey

import io.github.slashncode.nothingeats.platform.testing.ValkeyTestServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private class MovableClock(
    var now: Instant,
) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId) = this

    override fun instant() = now
}

class RateLimiterTest :
    FunSpec({
        val valkey = Valkey(ValkeyTestServer.config())
        beforeSpec { valkey.start() }
        afterSpec { valkey.stop() }

        val login = RateLimit("login", limit = 3, window = 1.minutes)

        fun limiter(clock: Clock = Clock.systemUTC()) = RateLimiter(valkey, prefix = "test-${UUID.randomUUID()}", clock = clock)

        test("allows up to the limit per subject, then says how long to wait") {
            val clock = MovableClock(Instant.parse("2026-10-25T12:00:15Z"))
            val limiter = limiter(clock)

            repeat(3) { limiter.attempt(login, "10.0.0.1") shouldBe RateDecision.Allowed }
            limiter.attempt(login, "10.0.0.2") shouldBe RateDecision.Allowed

            limiter.attempt(login, "10.0.0.1") shouldBe RateDecision.Limited(45.seconds)
        }

        test("a new window starts from zero") {
            val clock = MovableClock(Instant.parse("2026-10-25T12:00:59Z"))
            val limiter = limiter(clock)
            repeat(4) { limiter.attempt(login, "a@example.com") }

            clock.now = Instant.parse("2026-10-25T12:01:00Z")

            limiter.attempt(login, "a@example.com") shouldBe RateDecision.Allowed
        }

        test("peek decides without counting") {
            val limiter = limiter()

            repeat(5) { limiter.peek(login, "b@example.com") shouldBe RateDecision.Allowed }
            repeat(3) { limiter.attempt(login, "b@example.com") }

            limiter.peek(login, "b@example.com").shouldBeInstanceOf<RateDecision.Limited>()
        }

        test("keys hold no personal data and expire with the window") {
            val prefix = "test-${UUID.randomUUID()}"
            RateLimiter(valkey, prefix).attempt(login, "c@example.com")

            val keys = valkey.commands().keys("$prefix:*").get()
            keys.size shouldBe 1
            keys.single().contains("example") shouldBe false
            (valkey.commands().pttl(keys.single()).get() in 1..60_000) shouldBe true
        }

        test("fails open when Valkey is unavailable") {
            val offline = Valkey(ValkeyTestServer.config())

            RateLimiter(offline, "test").attempt(login, "d@example.com") shouldBe RateDecision.Allowed
        }

        test("the health check pings") {
            valkey.check().healthy shouldBe true
            Valkey(ValkeyTestServer.config()).check().healthy shouldBe false
        }
    })
