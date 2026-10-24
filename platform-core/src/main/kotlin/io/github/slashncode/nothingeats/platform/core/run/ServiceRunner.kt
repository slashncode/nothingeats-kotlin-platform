package io.github.slashncode.nothingeats.platform.core.run

import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/** Registers the code that runs when the process is asked to stop (SIGTERM). */
fun interface ShutdownHook {
    fun register(onShutdown: () -> Unit)
}

object JvmShutdownHook : ShutdownHook {
    override fun register(onShutdown: () -> Unit) {
        Runtime.getRuntime().addShutdownHook(Thread(onShutdown, "nothingeats-shutdown"))
    }
}

/**
 * Entry point shared by every service binary: one image, three modes.
 *
 * ```
 * fun main(args: Array<String>) = component.runner.run(args)
 * ```
 */
class ServiceRunner(
    private val lifecycle: LifecycleManager,
    private val migrations: MigrationRunner? = null,
    private val cronTasks: Set<CronTask> = emptySet(),
    private val shutdownHook: ShutdownHook = JvmShutdownHook,
) {
    private val log = LoggerFactory.getLogger(ServiceRunner::class.java)

    fun run(args: Array<String>) = runBlocking { run(RunMode.parse(args)) }

    suspend fun run(mode: RunMode) {
        log.info("Running in {} mode", mode)
        when (mode) {
            RunMode.Serve -> serve()
            RunMode.Migrate -> migrate()
            is RunMode.Cron -> cron(mode.task)
        }
    }

    private suspend fun serve() {
        val stopped = CompletableDeferred<Unit>()
        shutdownHook.register {
            runBlocking { lifecycle.stop() }
            stopped.complete(Unit)
        }
        lifecycle.start()
        stopped.await()
    }

    private suspend fun migrate() {
        val runner = migrations ?: throw IllegalStateException("This service has no database migrations")
        runner.migrate()
    }

    private suspend fun cron(name: String) {
        val task =
            cronTasks.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException(
                    "Unknown cron task '$name'. Known tasks: ${cronTasks.map { it.name }.sorted().ifEmpty { listOf("none") }}",
                )
        lifecycle.startInfrastructure()
        try {
            task.run()
        } finally {
            lifecycle.stop(drain = false)
        }
    }
}
