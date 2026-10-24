package io.github.slashncode.nothingeats.platform.core.lifecycle

import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness.State
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Starts components in [LifecycleComponent.order], flips readiness, then opens traffic via
 * [LifecycleComponent.onReady]. Stops in reverse order after a drain phase, so load balancers
 * see the instance as not ready before connections close.
 *
 * If anything fails while starting, the components that already started are stopped again
 * and the original exception is rethrown.
 */
class LifecycleManager(
    components: Set<LifecycleComponent>,
    private val readiness: Readiness,
    private val drainDelay: Duration = Duration.ZERO,
) {
    private val log = LoggerFactory.getLogger(LifecycleManager::class.java)
    private val ordered = components.sortedWith(compareBy<LifecycleComponent> { it.order }.thenBy { it.name })
    private val started = ArrayDeque<LifecycleComponent>()
    private val mutex = Mutex()

    /** Full start for serve mode. */
    suspend fun start() =
        mutex.withLock {
            check(readiness.state == State.STARTING) { "Lifecycle already ran (state ${readiness.state})" }
            rollbackOnFailure {
                ordered.forEach { startComponent(it) }
                readiness.moveTo(State.READY)
                log.info("Service ready ({} components)", ordered.size)
                ordered.forEach { it.onReady() }
            }
        }

    /** Starts only infrastructure components, without readiness or traffic. Used by cron mode. */
    suspend fun startInfrastructure() =
        mutex.withLock {
            check(readiness.state == State.STARTING) { "Lifecycle already ran (state ${readiness.state})" }
            rollbackOnFailure {
                ordered.filter { it.order < LifecycleOrder.HTTP_SERVER }.forEach { startComponent(it) }
            }
        }

    /** Drains (unless [drain] is false) and stops all started components in reverse order. Safe to call twice. */
    suspend fun stop(drain: Boolean = true) =
        mutex.withLock {
            if (readiness.state == State.STOPPED) return@withLock
            readiness.moveTo(State.DRAINING)
            if (drain && drainDelay.isPositive()) {
                log.info("Draining for {}", drainDelay)
                delay(drainDelay)
            }
            stopStarted()
            readiness.moveTo(State.STOPPED)
            log.info("Service stopped")
        }

    private suspend fun startComponent(component: LifecycleComponent) {
        log.info("Starting {}", component.name)
        component.start()
        started.addLast(component)
    }

    private suspend fun rollbackOnFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            log.error("Start failed, stopping {} started components", started.size, e)
            readiness.moveTo(State.DRAINING)
            stopStarted()
            readiness.moveTo(State.STOPPED)
            throw e
        }
    }

    private suspend fun stopStarted() {
        while (started.isNotEmpty()) {
            val component = started.removeLast()
            try {
                log.info("Stopping {}", component.name)
                component.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Stopping {} failed", component.name, e)
            }
        }
    }
}
