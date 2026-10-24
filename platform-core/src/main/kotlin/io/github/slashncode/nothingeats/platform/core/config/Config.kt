package io.github.slashncode.nothingeats.platform.core.config

import com.sksamuel.hoplite.ConfigLoader
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.sources.MapPropertySource

/**
 * Loads a typed config object from environment variables and fails fast: every missing or
 * malformed value is reported at once, and the service refuses to start.
 *
 * Naming: `__` separates nesting levels, a single `_` separates words inside a field name.
 * `DATABASE__MAXIMUM_POOL_SIZE=4` sets `database.maximumPoolSize`.
 *
 * Defaults live in the data classes (pointing at the local k3d stack); environment variables
 * override them. Use `com.sksamuel.hoplite.Secret` for secrets so they never appear in logs.
 */
inline fun <reified T : Any> loadConfig(env: Map<String, String> = System.getenv()): T = configLoader(env).loadConfigOrThrow<T>()

@OptIn(ExperimentalHoplite::class)
fun configLoader(env: Map<String, String>): ConfigLoader =
    ConfigLoaderBuilder
        .empty()
        .addDefaultDecoders()
        .addDefaultPreprocessors()
        .addDefaultNodeTransformers()
        .addDefaultParamMappers()
        .addDefaultParsers()
        // Sealed config types need an explicit `type` field instead of runtime guessing.
        .withExplicitSealedTypes()
        .addPropertySource(MapPropertySource(envTree(env)))
        .build()

private class Branch : LinkedHashMap<String, Any>()

/** Turns `HTTP__SHUTDOWN_TIMEOUT=30s` into `{http: {shutdown_timeout: 30s}}`. */
internal fun envTree(env: Map<String, String>): Map<String, Any> {
    val root = Branch()
    for ((name, value) in env) {
        val path = name.lowercase().split("__")
        if (path.any { it.isEmpty() }) continue
        var node = root
        for (segment in path.dropLast(1)) {
            node = node[segment] as? Branch ?: Branch().also { node[segment] = it }
        }
        // A nested group wins over a plain variable with the same name.
        if (node[path.last()] !is Branch) node[path.last()] = value
    }
    return root
}
