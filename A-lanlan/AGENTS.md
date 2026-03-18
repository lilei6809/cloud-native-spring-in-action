# Repository Guidelines

## Current Project Snapshot
This is no longer just the original three-service sample. The working repository is a multi-module Spring workspace with:

- `catalog-service/`: Spring Boot 3.4 REST service on port `9001`, JDBC + Flyway + PostgreSQL, package layout `config`, `domain`, `web`, `demo`.
- `order-service/`: Spring Boot 3.4 REST service on port `9002`, JDBC + Flyway + PostgreSQL, OpenFeign clients to `catalog-service`, virtual threads enabled.
- `config-service/`: Spring Cloud Config Server on port `8888`.
- `edge-server/`: Spring Boot 4 WebFlux gateway on port `9000` with Resilience4j, Redis-backed session/rate limiting, and fallback endpoints. It still exists even though Envoy Gateway is replacing much of its ingress role.
- `delivery-service/`: Spring Boot 4 starter service on port `9003`, Kafka + Spring Cloud Stream + Flyway. This module is early-stage; main code is mostly bootstrap/test scaffolding.
- `common/`: shared libraries published locally as `common-core` and `common-web`, consumed by the services.
- `envoy-gateway/`: Gateway API, EnvoyProxy, observability, and service manifests. Treat this as infra and ingress config, not a Java module.

Important: root `README.md` is chapter-era material and does not describe the current repo accurately enough for implementation work.

## Structure & Config Map
- Java code lives under each module's `src/main/java`; tests live under `src/test/java`.
- Runtime config files are local to each service:
  `catalog-service/src/main/resources/application.yml`,
  `order-service/src/main/resources/application.yaml`,
  `config-service/src/main/resources/application.yml`,
  `edge-server/src/main/resources/application.yaml`,
  `delivery-service/src/main/resources/application.yaml`.
- Externalized config files are under `config-repo/`.
- Database migrations are in:
  `catalog-service/src/main/resources/db/migration/`,
  `order-service/src/main/resources/db/migration/`,
  `delivery-service/src/main/resources/db/migration/`.
- Per-service Kubernetes manifests live in `catalog-service/k8s`, `config-service/k8s`, `order-service/k8s`, and `edge-server/k8s`.
- `envoy-gateway/` contains `component/`, `service/`, and `secret/` manifests for Envoy Gateway and related infra.
- `docker-compose.yml` is the local integration entry point for services plus PostgreSQL, Redis, Kafka, Kafka UI, Jaeger, and the legacy `edge-server`.

## Architecture Notes
- Services are independent Gradle projects; there is no root multi-project build for all services together.
- `common/` is its own multi-project Gradle build. `common-core` holds shared exception/result primitives; `common-web` provides web auto-configuration such as global exception handling.
- `catalog-service` and `order-service` follow a simple layered structure (`config` -> `domain` -> `web`) rather than full hexagonal architecture.
- `order-service` directly calls `catalog-service` via Feign; internal resilience concerns do not disappear just because ingress is moving to Envoy.
- `edge-server` and `envoy-gateway` currently coexist. Do not assume gateway concerns have been fully migrated unless you verify the specific policy/manifests involved.
- `config-service` points to a remote GitHub config repository in its own `application.yml`. Editing local `config-repo/` files does not automatically affect a running `config-service` unless the URI is changed or the local repo is used another way.

## Build, Test, and Local Workflow
- Run Gradle commands inside the module you are changing.
- Common commands:
  `./gradlew bootRun`, `./gradlew test`, `./gradlew build`, `./gradlew bootBuildImage`.
- Local dependencies:
  `docker compose up -d`.
- Before building services that depend on shared libraries, publish `common/` locally when needed:
  `cd common && ./gradlew publishToMavenLocal`.
- Example flow:
  `cd common && ./gradlew publishToMavenLocal`
  `cd ../order-service && ./gradlew test`

## Technology Baseline
- Java 21 across modules.
- Spring Boot split:
  Boot `3.4.13` in `catalog-service`, `order-service`, `config-service`, and `common-web` dependency alignment.
  Boot `4.0.3` in `edge-server` and `delivery-service`.
- Spring Cloud split:
  `2024.0.3` for the Boot 3 modules.
  `2025.1.0` for the Boot 4 modules.
- PostgreSQL + Flyway in catalog/order, and Flyway dependencies already added in delivery.
- Redis is used by `edge-server` for session/rate-limiter behavior.
- Kafka and Spring Cloud Stream are centered in `delivery-service`.
- Observability assets live in `envoy-gateway/` and include Envoy telemetry configuration plus OTEL/Jaeger support.

## Coding, Testing, and Review Expectations
- Use 4-space indentation and preserve the surrounding style.
- Follow the existing package naming conventions: `config`, `domain`, `web`, `demo`, and shared `commoncore` / `commonweb`.
- Prefer Spring naming conventions such as `*Controller`, `*Service`, `*Repository`, `*Properties`, `*Configuration`.
- Test stack is JUnit 5, Spring Boot Test, Testcontainers, MockWebServer, and module-specific test support.
- Add or update tests with behavior changes, especially around Flyway migrations, Feign integration, gateway/rate-limiter behavior, Kafka flows, and shared exception handling.
- Recent commits mix conventional prefixes and descriptive Chinese subjects. Keep commits focused and descriptive. In PRs, call out affected modules, config changes, schema changes, and verification commands.

## Security & Deployment Notes
- Do not commit secrets. Container publishing expects `GH_USERNAME` and `GHCR_TOKEN`.
- Treat `envoy-gateway/`, `edge-server/k8s`, and service `k8s/` manifests as deployment code; validate them before merge.
- Be explicit about whether a change targets local Docker Compose, legacy Spring gateway, or Kubernetes + Envoy Gateway, because all three deployment paths currently exist in this repository.
