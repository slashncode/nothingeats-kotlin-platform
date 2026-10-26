package io.github.slashncode.nothingeats.platform.valkey

import io.github.slashncode.nothingeats.platform.testing.ValkeyTestServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class ValkeyPubSubTest :
    FunSpec({
        test("a message reaches every pod subscribed to a matching pattern, and only those") {
            val prefix = "test-${UUID.randomUUID()}"
            val podA = ValkeyPubSub(ValkeyTestServer.config(), setOf("$prefix:live:*"))
            val podB = ValkeyPubSub(ValkeyTestServer.config(), setOf("$prefix:live:*"))
            val other = ValkeyPubSub(ValkeyTestServer.config(), setOf("$prefix:other:*"))
            listOf(podA, podB, other).forEach { it.start() }
            try {
                withTimeout(5.seconds) {
                    val atA =
                        async {
                            podA.messages
                                .filter { it.channel.startsWith(prefix) }
                                .take(2)
                                .toList()
                        }
                    val atB = async { podB.messages.first { it.channel.startsWith(prefix) } }
                    yield()

                    podA.publish("$prefix:live:user-1", """{"type":"order.updated"}""") shouldBe 2
                    podA.publish("$prefix:live:user-2", "second")

                    atA.await().map { it.channel } shouldBe listOf("$prefix:live:user-1", "$prefix:live:user-2")
                    atB.await() shouldBe ValkeyMessage("$prefix:live:user-1", """{"type":"order.updated"}""")
                }
                other.publish("$prefix:nobody", "x") shouldBe 0
            } finally {
                listOf(podA, podB, other).forEach { it.stop() }
            }
        }
    })
