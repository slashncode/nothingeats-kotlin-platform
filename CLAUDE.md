# CLAUDE.md

Shared Kotlin platform for NothingEats services. The cross-repo plan and session rules live in `../CLAUDE.md` (workspace root), if present — read it first.

## Commands

- `./gradlew build` — compile (warnings are errors), ktlint, all tests. Persistence tests need Docker (Testcontainers).
- `./gradlew :platform-core:test` — one module's tests
- `./gradlew ktlintFormat` — auto-format before committing
- `./gradlew publishToMavenLocal` — publish all modules to `~/.m2`

## Layout

- `gradle/libs.versions.toml` — the only place for versions, also published as `version-catalog`. `nothingeats-platform` is the version of every module.
- `build-logic/` — convention plugins (included build). Third-party Gradle plugins on its classpath can't be applied by id from module builds; wrap them in a convention plugin (see `nothingeats.kotlinx-serialization`).
- `platform-*` — the modules; package root `io.github.slashncode.nothingeats.platform.<module>`.

## Rules

- Keep modules thin and DI-agnostic: plain classes, no kotlin-inject in the platform. Services wire them in their composition root.
- No global mutable singletons. The one exception is the shared Testcontainers container in `platform-testing`.
- Never catch `CancellationException` without rethrowing it.
- Classify SQL errors by SQLSTATE, never by message text.
- Every public behaviour gets a Kotest spec; DB behaviour is tested against real Postgres via `PostgresTestDatabases`.
- Library APIs verified against the actual jars (hoplite 3, Exposed 1.6 `org.jetbrains.exposed.v1.*`, Testcontainers 2 `org.testcontainers.postgresql.*`) — check the jar before assuming an older API.
