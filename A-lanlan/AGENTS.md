# Repository Guidelines

## Project Structure & Module Organization
This workspace contains several independent Gradle-based Spring services: `catalog-service/`, `order-service/`, `config-service/`, `edge-server/`, and `delivery-service/`. Shared code lives in `common/` (`common-core`, `common-web`). Each module keeps production code in `src/main` and tests in `src/test`. Environment-backed configuration is stored in `config-repo/`. Deployment assets live in module-level `k8s/`, `polar-deployment/`, and `envoy-gateway/`. Root documentation and design notes are under `docs/`.

## Build, Test, and Development Commands
Run commands from the module you are changing unless noted otherwise.

- `./gradlew bootRun`: start a Spring service locally.
- `./gradlew build`: compile, test, and package the module.
- `./gradlew test`: run the JUnit 5 suite only.
- `./gradlew bootBuildImage --imageName ghcr.io/<user>/<service>:<tag>`: build an OCI image.
- `docker compose up -d` from the repository root: start local dependencies such as PostgreSQL.

Example: `cd order-service && ./gradlew test`

## Coding Style & Naming Conventions
Use 4-space indentation and keep formatting consistent with the surrounding file. Most modules target Java 21; preserve each module's existing Spring Boot and dependency choices rather than normalizing by hand. Keep packages layered (`config`, `domain`, `web`, `demo`) and follow Spring naming conventions such as `*Controller`, `*Service`, `*Repository`, and `*Properties`. Put environment-specific settings in config files or `config-repo/`, not in Java code.

## Testing Guidelines
The test stack is JUnit 5 with Spring Boot Test; several modules also use Testcontainers and migration tests. Place tests under `src/test/java` and name them `*Test` or `*Tests`. Add or update tests for every behavior change, especially around persistence, HTTP contracts, Kafka flows, and Flyway migrations. Before opening a PR, run `./gradlew test` in every affected module.

## Commit & Pull Request Guidelines
Recent history favors short, imperative subjects with a scoped prefix when helpful, for example `docs: add envoy gateway migration docs` or `envoy-gateway: 初尝试`. Keep commits focused to one logical change. PRs should state the purpose, list impacted modules, include validation commands, and call out config, schema, or Kubernetes manifest changes.

## Security & Configuration Tips
Do not commit secrets. Use environment variables such as `GH_USERNAME` and `GHCR_TOKEN` for registry access. Validate Kubernetes YAML before merge; CI expects strict manifest checks such as `kubeconform --strict k8s`.
