# nothingeats-kotlin-platform

Shared building blocks for every NothingEats Kotlin service: one repo, **one version**, one BOM.

Part of [NothingEats](https://github.com/slashncode/nothingeats).

## Modules

| Artifact | What it gives a service |
|---|---|
| `platform-core` | Ordered lifecycle with a readiness gate, health checks, `serve` / `migrate` / `cron <task>` run modes, fail-fast typed config from env vars, the `AppError` model with stable `UPPER_SNAKE` codes |
| `platform-http` | Ktor server as a lifecycle component, RFC 9457 `application/problem+json` errors, `X-Request-Id` handling, `/health/live` and `/health/ready`, JWT verification against the identity service's JWKS, secure-by-default `public {}` / `authenticated {}` / `requireRole {}` routes (an unguarded route stops the start) |
| `platform-persistence` | Postgres pool + Exposed, Flyway migration sets (run only in `migrate` mode, verified on start), SQLSTATE-based error helpers, explicit retry for serialization failures |
| `platform-kafka` | CloudEvents envelope, transactional outbox + relay (`FOR UPDATE SKIP LOCKED`), consumer with inbox deduplication, retries and a dead-letter topic, `cron purge-messaging` |
| `ktor-posthog` | `ProductAnalytics` on the official PostHog server SDK: server events with the browser's distinct id and `$session_id`, feature flags, unexpected exceptions to error tracking |
| `platform-testing` | Shared Postgres and Kafka containers with a fresh database / topic per test, `TestTokens` for signed access tokens, `RecordingAnalytics` |
| `platform-bom` | Aligns all platform modules and the libraries they expose |
| `version-catalog` | The shared `libs.versions.toml` |
| `build-logic` | Convention plugins: `nothingeats.kotlin-base`, `nothingeats.kotlin-library`, `nothingeats.kotlin-service`, `nothingeats.kotlinx-serialization`, `nothingeats.publishing` |

## Using it from a service

A service's `settings.gradle.kts` uses the local checkout when it sits next to the service (composite build, changes are picked up without publishing), otherwise the published artifacts in the version from `gradle.properties` (`nothingeatsPlatformVersion`):

```kotlin
pluginManagement {
    val platformDir = file("../nothingeats-kotlin-platform")
    val platformVersion = providers.gradleProperty("nothingeatsPlatformVersion").get()
    if (platformDir.exists()) {
        includeBuild(platformDir.resolve("build-logic"))
    } else {
        resolutionStrategy {
            eachPlugin { if (requested.id.namespace == "nothingeats") useVersion(platformVersion) }
        }
    }
    // repositories: gradlePluginPortal, mavenCentral, GitHub Packages when not local
}

val platformDir = file("../nothingeats-kotlin-platform")
if (platformDir.exists()) includeBuild(platformDir)

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            if (platformDir.exists()) {
                from(files(platformDir.resolve("gradle/libs.versions.toml")))
            } else {
                from("io.github.slashncode.nothingeats:version-catalog:${providers.gradleProperty("nothingeatsPlatformVersion").get()}")
            }
        }
    }
}
```

The full version, including the GitHub Packages repositories, is in `nothingeats-kotlin-service-template/settings.gradle.kts`.

## Conventions

- **Config**: data classes with local defaults, overridden by env vars. `__` nests, `_` separates words: `DATABASE__MAXIMUM_POOL_SIZE=4` sets `database.maximumPoolSize`. Secrets use hoplite's `Secret`.
- **Lifecycle**: components declare an `order`; consumers and servers that take traffic open in `onReady`, after readiness is up. Components are collected via DI multibinding, never listed by hand.
- **Run modes**: one image, `serve` (default), `migrate` (Helm pre-upgrade Job), `cron <task>` (CronJob; starts infrastructure only).
- **Errors**: throw `AppError` subtypes for expected failures; everything else becomes a generic 500 that never leaks its message.
- **Exposed**: the blind retry of every SQL error is disabled; `transactionWithRetry` retries only serialization failures and deadlocks.

## Development

```bash
./gradlew build                  # compile, ktlint, tests (Docker needed for Testcontainers)
./gradlew ktlintFormat           # fix formatting
./gradlew publishToMavenLocal    # try a change in a service without the composite build
```

Versions live in `gradle/libs.versions.toml` only; the platform version is `nothingeats-platform` there.

## License

MIT
