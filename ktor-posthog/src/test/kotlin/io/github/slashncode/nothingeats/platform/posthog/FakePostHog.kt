package io.github.slashncode.nothingeats.platform.posthog

import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream

/** Accepts what the PostHog SDK sends (batches, possibly gzip-compressed) and keeps the events. */
class FakePostHog : Closeable {
    private val port = ServerSocket(0).use { it.localPort }
    private val received = CopyOnWriteArrayList<JsonObject>()
    private val calls = CopyOnWriteArrayList<Call>()

    /** A request to the private API (anything but ingestion). */
    data class Call(
        val path: String,
        val authorization: String?,
        val body: JsonObject,
    )

    /** Status for private API calls, e.g. 500 to test retries. */
    @Volatile var apiStatus = HttpStatusCode.Accepted

    val url = "http://localhost:$port"

    private val server =
        embeddedServer(Netty, port = port) {
            routing {
                post("{...}") {
                    val raw = call.receiveChannel().toByteArray()
                    val body =
                        if (call.request.header("Content-Encoding") ==
                            "gzip"
                        ) {
                            GZIPInputStream(raw.inputStream()).readBytes()
                        } else {
                            raw
                        }
                    val json = Json.parseToJsonElement(body.decodeToString()).jsonObject
                    if (call.request.uri.startsWith("/api/")) {
                        calls += Call(call.request.uri, call.request.header("Authorization"), json)
                        call.respondText("{}", status = apiStatus)
                        return@post
                    }
                    json["batch"]?.jsonArray?.forEach { received += it.jsonObject } ?: received.add(json)
                    call.respondText("""{"status":"Ok"}""")
                }
            }
        }.start(wait = false)

    fun events(): List<JsonObject> = received.toList()

    fun apiCalls(): List<Call> = calls.toList()

    override fun close() = server.stop(0, 0)
}
