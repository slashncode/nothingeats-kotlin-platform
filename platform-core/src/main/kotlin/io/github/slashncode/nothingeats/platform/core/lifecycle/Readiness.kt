package io.github.slashncode.nothingeats.platform.core.lifecycle

import java.util.concurrent.atomic.AtomicReference

/**
 * The service-wide lifecycle state behind the liveness and readiness probes.
 * Only [LifecycleManager] changes it.
 */
class Readiness {
    enum class State { STARTING, READY, DRAINING, STOPPED }

    private val current = AtomicReference(State.STARTING)

    val state: State get() = current.get()

    /** Traffic may be routed to this instance. */
    val isReady: Boolean get() = state == State.READY

    /** The process is healthy enough not to be restarted. */
    val isLive: Boolean get() = state != State.STOPPED

    internal fun moveTo(next: State) {
        current.set(next)
    }
}
