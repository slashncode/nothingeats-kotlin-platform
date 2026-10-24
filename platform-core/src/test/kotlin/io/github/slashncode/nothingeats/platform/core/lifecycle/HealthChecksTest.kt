package io.github.slashncode.nothingeats.platform.core.lifecycle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.awaitCancellation
import kotlin.time.Duration.Companion.milliseconds

private fun check(
    name: String,
    block: suspend () -> HealthStatus,
) = object : HealthCheck {
    override val name = name

    override suspend fun check() = block()
}

class HealthChecksTest :
    FunSpec({
        test("healthy when every check is up") {
            val report = HealthChecks(setOf(check("db") { HealthStatus.UP })).run()

            report.healthy shouldBe true
        }

        test("a throwing check counts as down without failing the others") {
            val report =
                HealthChecks(
                    setOf(
                        check("db") { HealthStatus.UP },
                        check("broker") { error("boom") },
                    ),
                ).run()

            report.healthy shouldBe false
            report.checks.getValue("db") shouldBe HealthStatus.UP
            report.checks.getValue("broker").healthy shouldBe false
        }

        test("a hanging check times out") {
            val report = HealthChecks(setOf(check("slow") { awaitCancellation() }), timeout = 50.milliseconds).run()

            report.checks.getValue("slow").detail shouldBe "timed out after 50ms"
        }
    })
