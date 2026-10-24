package io.github.slashncode.nothingeats.platform.core.run

import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleManager
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private class Component(
    override val name: String,
    override val order: Int,
    private val events: MutableList<String>,
) : LifecycleComponent {
    override suspend fun start() {
        events += "start $name"
    }

    override suspend fun stop() {
        events += "stop $name"
    }
}

class ServiceRunnerTest :
    FunSpec({
        test("parses run modes") {
            RunMode.parse(emptyArray()) shouldBe RunMode.Serve
            RunMode.parse(arrayOf("serve")) shouldBe RunMode.Serve
            RunMode.parse(arrayOf("migrate")) shouldBe RunMode.Migrate
            RunMode.parse(arrayOf("cron", "rotate-keys")) shouldBe RunMode.Cron("rotate-keys")
            shouldThrow<IllegalArgumentException> { RunMode.parse(arrayOf("cron")) }
            shouldThrow<IllegalArgumentException> { RunMode.parse(arrayOf("deploy")) }.message shouldContain "deploy"
        }

        test("serve runs until the shutdown hook fires, then stops everything") {
            val events = mutableListOf<String>()
            val hookRegistered = CompletableDeferred<() -> Unit>()
            val runner =
                ServiceRunner(
                    lifecycle = LifecycleManager(setOf(Component("db", LifecycleOrder.INFRASTRUCTURE, events)), Readiness()),
                    shutdownHook = { hookRegistered.complete(it) },
                )

            coroutineScope {
                val serving = async { runner.run(RunMode.Serve) }
                val onShutdown = hookRegistered.await()
                withContext(Dispatchers.IO) { onShutdown() }
                serving.await()
            }

            events shouldContainExactly listOf("start db", "stop db")
        }

        test("migrate runs the migrations without starting the lifecycle") {
            val events = mutableListOf<String>()
            val runner =
                ServiceRunner(
                    lifecycle = LifecycleManager(setOf(Component("db", LifecycleOrder.INFRASTRUCTURE, events)), Readiness()),
                    migrations = { events += "migrated" },
                )

            runner.run(RunMode.Migrate)

            events shouldContainExactly listOf("migrated")
        }

        test("cron starts infrastructure only, runs the task and stops") {
            val events = mutableListOf<String>()
            val task =
                object : CronTask {
                    override val name = "cleanup"

                    override suspend fun run() {
                        events += "cleanup"
                    }
                }
            val runner =
                ServiceRunner(
                    lifecycle =
                        LifecycleManager(
                            setOf(
                                Component("db", LifecycleOrder.INFRASTRUCTURE, events),
                                Component("http", LifecycleOrder.HTTP_SERVER, events),
                            ),
                            Readiness(),
                        ),
                    cronTasks = setOf(task),
                )

            runner.run(RunMode.Cron("cleanup"))

            events shouldContainExactly listOf("start db", "cleanup", "stop db")
        }

        test("unknown cron task names the known ones") {
            val runner = ServiceRunner(LifecycleManager(emptySet(), Readiness()))

            shouldThrow<IllegalArgumentException> { runner.run(RunMode.Cron("nope")) }.message shouldContain "[none]"
        }
    })
