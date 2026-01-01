# Repository Guidelines

## Project Structure & Modules
- `backend/`: Ktor entrypoint (`Server.kt`), DI wiring, HTTP routes, config loader using `application.yaml`.
- `core/`: Domain models (`Document`, `HybridWeights`) and port interfaces for search, answers, and repositories.
- `infra/db/`: Postgres/pgvector persistence via Exposed, Flyway migrations, Testcontainers-backed integration tests.
- `infra/ai/` and `infra/search/`: AI client stubs and hybrid search/answer/backfill services composed from core ports.
- `utils/`: Small shared helpers with unit tests; `buildSrc/`: Gradle convention plugin and version catalog helpers.
- `test/e2e/`: Cross-module tests; `specs/` and `SPECS.md`: product/editorial docs; `docker-compose.yml`: local pgvector + Adminer.

## Build, Test, and Run Commands
- `./gradlew build` – Compile all modules and run unit/integration tests.
- `./gradlew check` – Run the full verification suite (includes tests and static checks configured by plugins).
- `./gradlew :backend:run` – Start the HTTP server locally on port 8080 using `application.yaml`.
- `./gradlew :infra:db:test` – Run DB/Testcontainers integration tests; requires Docker or start `docker-compose up db`.
- `SKIP_DB_TESTS=true ./gradlew test` – Skip containerized DB tests when Docker is unavailable.

## Coding Style & Naming Conventions
- Kotlin, 4-space indentation, and idiomatic null-safety. Keep functions small and favor data classes for DTOs.
- Package prefix `org.themessagesearch.*`; file names align with top-level class/object. Tests end with `*Test.kt`.
- Use `kotlinx.serialization` for request/response payloads and avoid manual JSON handling. Prefer immutable vals and dependency injection via `ServiceRegistry`.
- Manage dependencies through `gradle/libs.versions.toml`; do not hardcode versions in module build scripts.

## Testing Guidelines
- Frameworks: Kotlin Test + JUnit 5 (via Gradle’s `useJUnitPlatform`). Write focused unit tests near implementation; integration tests belong in `infra` or `test/e2e`.
- Name tests descriptively; backtick-style names are welcome for behavior (“`migration V1 created tables`”).
- DB tests require Docker; bring up pgvector with `docker-compose up db` or set `SKIP_DB_TESTS=true` to bypass.
- Prefer deterministic fixtures; when generating random data (e.g., vectors) keep sizes consistent with production defaults (1536 dims).

## Commit & Pull Request Guidelines
- Commit messages: short, imperative sentences (e.g., “Add dimension validation for embeddings”). Squash noisy WIP commits before raising a PR.
- PRs: include a concise summary, linked issue/ticket, test commands executed, and screenshots or curl examples for API changes. Call out DB migrations or config changes explicitly.
- Keep changes scoped; align new modules with existing Gradle convention plugin (`buildsrc.convention.kotlin-jvm`).

## Security & Configuration Tips
- Never commit secrets. Override sensitive defaults from `backend/src/main/resources/application.yaml` via env vars (`DB_PASSWORD`, `JWT_SECRET`, `AI_API_KEY`).
- JWT auth is enforced on API routes; use the configured issuer/audience when generating tokens for local testing.
- Verify new endpoints expose `/health` and `/metrics` unaffected; avoid logging secrets in exceptions or call logs.
