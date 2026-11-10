package io.github.slashncode.nothingeats.platform.posthog

import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** PostHog's private API (the app host), for person erasure; ingestion and flags are posthog-ktor's `PostHogFake`. */
class PrivateApiFake : Closeable {
    private val port = ServerSocket(0).use { it.localPort }
    private val received = CopyOnWriteArrayList<Call>()

    data class Call(
        val path: String,
        val authorization: String?,
        val body: JsonObject,
    )

    /** What every call is answered with, e.g. 500 to test retries. */
    @Volatile var status = HttpStatusCode.Accepted

    val url = "http://localhost:$port"

    private val server =
        embeddedServer(Netty, port = port) {
            routing {
                post("{...}") {
                    received +=
                        Call(call.request.uri, call.request.header("Authorization"), Json.parseToJsonElement(call.receiveText()).jsonObject)
                    call.respondText("{}", status = status)
                }
            }
        }.start(wait = false)

    fun calls(): List<Call> = received.toList()

    override fun close() = server.stop(0, 0)
}
