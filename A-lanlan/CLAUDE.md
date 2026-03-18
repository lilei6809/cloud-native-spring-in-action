# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Context

This is a personal learning codebase for the book *Cloud Native Spring in Action*. Rather than using the per-chapter `begin/` directories in the parent repo, all chapter content is incrementally added here. The goal is a single, growing codebase that accumulates each chapter's features.

## Services

| Service | Port | Database | Notes |
|---------|------|----------|-------|
| `config-service` | 8888 | — | Git-backed config repo |
| `catalog-service` | 9001 | `polardb_catalog` (PostgreSQL) | Spring MVC |
| `order-service` | 9002 | `polardb_order` (PostgreSQL) | Spring MVC + Feign |
| `delivery-service` | 9003 | — | Kafka consumer/producer (Spring Cloud Stream) |

The `edge-server` (Spring Cloud Gateway) has been **replaced by Envoy Gateway** as the ingress layer. `edge-server/` is kept for reference only.

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
- Custom config properties via `PolarProperties` (`polar.greeting`) and `K8sProperties` (pod name injection)
- `BookDataLoader` seeds test data when `testdata` profile is active
- `MdcContextFilter` reads `x-tanant-id` / `x-user-id` headers and puts them into MDC for structured logging

### order-service
- Same layered structure; uses Lombok (catalog-service does not)
- OpenFeign clients in `config/`: `CatalogClient` (short timeout) and `CatalogLongRequestClient` (4s timeout)
- `FeignExceptionDecoder` + `FeignHeaderInterceptor` for cross-service error handling
- `FeignMdcIntercepter` propagates MDC context (`tenantId`, `userId`) as request headers on outbound Feign calls
- `MdcContextFilter` reads MDC headers back on the receiving end
- Uses Spring Virtual Threads (`spring.threads.virtual.enabled: true`)

### edge-server (retired — kept for reference)
- Replaced by Envoy Gateway; do not extend or deploy
- Was: Spring Cloud Gateway with Resilience4j circuit breakers, Redis rate limiting, Redis sessions

### delivery-service
- Spring Cloud Stream with Kafka binder (consumer + producer)
- Uses Lombok and Virtual Threads
- Currently a skeleton — no domain logic yet

### config-service
- Thin Spring Cloud Config Server; reads from `https://github.com/PolarBookshop/config-repo`
- No application logic — config only

## Observability Stack (Kubernetes / `envoy-gateway/infra.yaml`)

The infra manifest deploys:

| Component | Purpose |
|-----------|---------|
| OpenTelemetry Collector | Receives OTLP traces + logs; fans out to Jaeger and Loki |
| Jaeger | Distributed tracing UI |
| Loki | Log aggregation |
| OTel auto-instrumentation | `Instrumentation` CRD injects the Java agent into pods |

**Trace/log flow:** App (OTLP via Java agent) → OTel Collector → Jaeger (traces) / Loki (logs)

The OTel Collector uses **tail sampling**: keeps all error traces and slow requests (>1s), drops the rest.

### Envoy Gateway (`envoy-gateway/`)
The active ingress layer, replacing `edge-server`. Uses Envoy Proxy via the Kubernetes Envoy Gateway operator:
- `infra.yaml` — shared infrastructure (Postgres, Redis, MongoDB, OTel Collector, Jaeger)
- `component/envoy-tracing-logging.yaml` — `EnvoyProxy` config for tracing (OTel → otel-collector:4317) and structured JSON access logging to stdout + OTel
- `component/gateway.yaml` / `component/loki.yaml` — Gateway and Loki deployments
- `service/catalog-service.yaml` / `service/order-service.yaml` — `HTTPRoute` resources routing traffic to services

### Structured Logging / MDC Pattern
Both `catalog-service` and `order-service` use `MdcContextFilter` to inject business context into the MDC before each request:
- `x-tenant-id` header → MDC `tenantId`
- `x-user-id` header → MDC `userId`
- Trace/span IDs are automatically populated by the OTel Java agent

When `order-service` calls `catalog-service` via Feign, `FeignMdcIntercepter` copies MDC values into outbound request headers so context is not lost across service boundaries.

Logback is configured to output JSON (via `logback-spring.xml`) in both services so logs can be parsed by Loki.

## CI/CD

Each service has `.github/workflows/commit-stage.yml` that:
1. Builds + tests on every push
2. Validates Kubernetes manifests with `kubeconform`
3. On `main` branch: builds and publishes container image to GHCR

Kubernetes manifests are in each service's `k8s/` directory. The `envoy-gateway/` directory contains cluster-wide infra and Envoy Gateway resources applied separately.