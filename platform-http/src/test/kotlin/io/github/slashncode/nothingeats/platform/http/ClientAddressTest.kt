package io.github.slashncode.nothingeats.platform.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

class ClientAddressTest :
    FunSpec({
        test("takes the entry the gateway added, not what the client claims") {
            var seen = ""
            testApplication {
                routing { get("/") { call.respondText(call.clientAddress) } }
                seen = client.get("/") { header("X-Forwarded-For", "1.2.3.4, 10.42.0.7") }.bodyAsText()
            }
            seen shouldBe "10.42.0.7"
        }

        test("falls back to the TCP peer without the header") {
            var seen = ""
            testApplication {
                routing { get("/") { call.respondText(call.clientAddress) } }
                seen = client.get("/").bodyAsText()
            }
            seen shouldBe "localhost"
        }
    })
