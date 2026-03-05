# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Context

This is a personal learning codebase for the book *Cloud Native Spring in Action*. Rather than using the per-chapter `begin/` directories in the parent repo, all chapter content is incrementally added here. The goal is a single, growing codebase that accumulates each chapter's features.

## Services

| Service | Port | Database |
|---------|------|----------|
| `config-service` | 8888 | — (Git-backed config repo) |
| `catalog-service` | 9001 | `polardb_catalog` (PostgreSQL) |
| `order-service` | 9002 | `polardb_order` (PostgreSQL) |

**Startup order:** `config-service` → `catalog-service` / `order-service`

`order-service` calls `catalog-service` via OpenFeign (`CatalogClient` / `CatalogLongRequestClient`) at `polar.catalog-service-uri`.

## Common Modules (`common/`)

Two shared libraries published to `mavenLocal()` — must be built first if changed:

- **`common-core`** — `ResultBox<T>` (unified API response wrapper), `BusinessException`, `SystemException`
- **`common-web`** — `GlobalExceptionHandler` (Spring Boot auto-configuration via `CommonWebAutoConfiguration`)

Only `order-service` currently depends on these. To publish: run `./gradlew publishToMavenLocal` inside `common/common-core` or `common/common-web`.

## Build & Test Commands

Run all commands from the individual service directory (e.g., `cd catalog-service`).

```bash
# Build and run all tests
./gradlew build

# Run tests only
./gradlew test

# Run a single test class
./gradlew test --tests "com.polarbookshop.catalogservice.domain.BookServiceTest"

# Run the application locally (activates 'testdata' profile which seeds demo data)
./gradlew bootRun

# Build and push container image via Jib (requires GH_USERNAME and GHCR_TOKEN env vars)
./gradlew jib
```

Integration tests use Testcontainers (PostgreSQL) — Docker must be running.

## Architecture Notes

### catalog-service
- Standard layered Spring MVC: `web` → `domain` (service + repository) pattern
- Spring Data JDBC with Flyway migrations (`resources/db/migration/`)
- Custom config properties via `PolarProperties` (`polar.greeting`)
- `BookDataLoader` seeds test data when `testdata` profile is active

### order-service
- Same layered structure; uses Lombok (catalog-service does not)
- OpenFeign clients in `config/`: `CatalogClient` (short timeout) and `CatalogLongRequestClient` (4s timeout)
- `FeignExceptionDecoder` + `FeignHeaderInterceptor` for cross-service error handling
- Uses Spring Virtual Threads (`spring.threads.virtual.enabled: true`)

### config-service
- Thin Spring Cloud Config Server; reads from `https://github.com/PolarBookshop/config-repo`
- No application logic — config only

## CI/CD

Each service has `.github/workflows/commit-stage.yml` that:
1. Builds + tests on every push
2. Validates Kubernetes manifests with `kubeconform`
3. On `main` branch: builds and publishes container image to GHCR

Kubernetes manifests are in each service's `k8s/` directory.