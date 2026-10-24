package io.github.slashncode.nothingeats.platform.core.run

/** How a service binary was started: `serve` (default), `migrate`, or `cron <task>`. */
sealed interface RunMode {
    data object Serve : RunMode

    data object Migrate : RunMode

    data class Cron(
        val task: String,
    ) : RunMode

    companion object {
        fun parse(args: Array<String>): RunMode =
            when (val mode = args.firstOrNull()) {
                null, "serve" -> Serve
                "migrate" -> Migrate
                "cron" -> Cron(args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException(USAGE))
                else -> throw IllegalArgumentException("Unknown mode '$mode'. $USAGE")
            }

        private const val USAGE = "Usage: serve | migrate | cron <task>"
    }
}

/** A database migration step, run by `migrate` mode (as a Kubernetes Job), never by serving pods. */
fun interface MigrationRunner {
    suspend fun migrate()
}

/** A task run by `cron <name>` mode (as a Kubernetes CronJob). */
interface CronTask {
    val name: String

    suspend fun run()
}
