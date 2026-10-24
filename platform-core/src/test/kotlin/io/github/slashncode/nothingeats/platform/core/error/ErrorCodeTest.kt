package io.github.slashncode.nothingeats.platform.core.error

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class ErrorCodeTest :
    FunSpec({
        context("accepts UPPER_SNAKE_CASE") {
            withData("NOT_FOUND", "DISH_NOT_FOUND", "RATE_LIMITED", "E2E_FAILURE") { value ->
                ErrorCode(value).value shouldBe value
            }
        }

        context("rejects anything else") {
            withData("not_found", "NotFound", "_LEADING", "TRAILING_", "DOUBLE__UNDERSCORE") { value ->
                shouldThrow<IllegalArgumentException> { ErrorCode(value) }
            }
        }

        test("rejects an empty code") {
            shouldThrow<IllegalArgumentException> { ErrorCode("") }
        }
    })
