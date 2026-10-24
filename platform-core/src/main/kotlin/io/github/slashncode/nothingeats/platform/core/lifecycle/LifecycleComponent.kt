package io.github.slashncode.nothingeats.platform.core.lifecycle

/**
 * A part of a service that has to be started and stopped in a defined order.
 *
 * - [start] runs before the service reports ready: open pools, verify the schema, connect clients.
 * - [onReady] runs once every component has started and readiness is up: open traffic sources
 *   such as Kafka consumers or the gRPC server here, so nothing is consumed by a half-started service.
 * - [stop] runs in reverse start order on shutdown.
 *
 * Components are collected by DI (multibinding), never listed by hand.
 */
interface LifecycleComponent {
    val name: String
    val order: Int

    suspend fun start() {}

    suspend fun onReady() {}

    suspend fun stop() {}
}

/** Well-known start orders. Lower starts first and stops last. */
object LifecycleOrder {
    /** Pools and clients. The only components started in cron mode. */
    const val INFRASTRUCTURE = 100

    /** The HTTP server, early so health probes answer while the rest starts. */
    const val HTTP_SERVER = 200

    /** Application-level components that need infrastructure. */
    const val APPLICATION = 300

    /** Traffic sources that open in [LifecycleComponent.onReady]. */
    const val TRAFFIC = 400
}
