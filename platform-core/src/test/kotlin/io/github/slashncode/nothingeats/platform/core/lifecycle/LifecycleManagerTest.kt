package io.github.slashncode.nothingeats.platform.core.lifecycle

import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness.State
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private class RecordingComponent(
    override val name: String,
    override val order: Int,
    private val events: MutableList<String>,
    private val readiness: Readiness? = null,
    private val failOnStart: Boolean = false,
) : LifecycleComponent {
    override suspend fun start() {
        if (failOnStart) error("$name failed")
        events += "start $name"
    }

    override suspend fun onReady() {
        events += "ready $name (${readiness?.state})"
    }

    override suspend fun stop() {
        events += "stop $name"
    }
}

class LifecycleManagerTest :
    FunSpec({
        test("starts in order, opens traffic after readiness, stops in reverse") {
            val events = mutableListOf<String>()
            val readiness = Readiness()
            val manager =
                LifecycleManager(
                    setOf(
                        RecordingComponent("consumer", LifecycleOrder.TRAFFIC, events, readiness),
                        RecordingComponent("db", LifecycleOrder.INFRASTRUCTURE, events),
                        RecordingComponent("http", LifecycleOrder.HTTP_SERVER, events),
                    ),
                    readiness,
                )

            manager.start()
            readiness.state shouldBe State.READY
            manager.stop()

            events shouldContainExactly
                listOf(
                    "start db",
                    "start http",
                    "start consumer",
                    "ready db (null)",
                    "ready http (null)",
                    "ready consumer (READY)",
                    "stop consumer",
                    "stop http",
                    "stop db",
                )
            readiness.state shouldBe State.STOPPED
        }

        test("a failing start stops what already started and rethrows") {
            val events = mutableListOf<String>()
            val readiness = Readiness()
            val manager =
                LifecycleManager(
                    setOf(
                        RecordingComponent("db", LifecycleOrder.INFRASTRUCTURE, events),
                        RecordingComponent("http", LifecycleOrder.HTTP_SERVER, events, failOnStart = true),
                        RecordingComponent("consumer", LifecycleOrder.TRAFFIC, events),
                    ),
                    readiness,
                )

            shouldThrow<IllegalStateException> { manager.start() }.message shouldBe "http failed"

            events shouldContainExactly listOf("start db", "stop db")
            readiness.state shouldBe State.STOPPED
        }

        test("cron mode starts only infrastructure and never becomes ready") {
            val events = mutableListOf<String>()
            val readiness = Readiness()
            val manager =
                LifecycleManager(
                    setOf(
                        RecordingComponent("db", LifecycleOrder.INFRASTRUCTURE, events),
                        RecordingComponent("http", LifecycleOrder.HTTP_SERVER, events),
                    ),
                    readiness,
                )

            manager.startInfrastructure()
            readiness.state shouldBe State.STARTING
            manager.stop(drain = false)

            events shouldContainExactly listOf("start db", "stop db")
        }

        test("stop is idempotent") {
            val events = mutableListOf<String>()
            val manager = LifecycleManager(setOf(RecordingComponent("db", 1, events)), Readiness())

            manager.start()
            manager.stop()
            manager.stop()

            events shouldContainExactly listOf("start db", "ready db (null)", "stop db")
        }
    })
