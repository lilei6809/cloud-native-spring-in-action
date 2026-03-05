# Repository Guidelines

## Project Structure & Module Organization
This repository is an integrated learning workspace with three independent Spring Boot services:
- `catalog-service/`: book catalog API (port `9001`), Flyway migrations in `src/main/resources/db/migration`.
- `order-service/`: order API (port `9002`), calls catalog via OpenFeign.
- `config-service/`: Spring Cloud Config Server (port `8888`).

Each service is a standalone Gradle project (`build.gradle`, `settings.gradle`, `gradlew`, `src/main`, `src/test`).
Shared configuration files live in `config-repo/`. Deployment assets are in `polar-deployment/` and per-service `k8s/` folders. Root `docker-compose.yaml` provides local PostgreSQL.

## Build, Test, and Development Commands
Run commands inside the target service directory.

- `./gradlew bootRun`: start the service locally.
- `./gradlew build`: compile, run tests, and package.
- `./gradlew test`: run JUnit 5 test suite.
- `./gradlew bootBuildImage --imageName ghcr.io/<user>/<service>:<tag>`: build OCI image.
- `docker compose up -d` (repo root): start local PostgreSQL.

Example:
```bash
cd catalog-service && ./gradlew bootRun
```

## Coding Style & Naming Conventions
- Language baseline: Java 21, Spring Boot 3.4.x.
- Use consistent formatting per file; prefer 4-space indentation for new code.
- Keep package layering clear: `config`, `domain`, `web`, `demo`.
- Follow Spring naming patterns: `*Controller`, `*Service`, `*Repository`, `*Properties`.
- Keep YAML concise and environment-specific overrides in config files, not hardcoded values.

## Testing Guidelines
- Test stack: JUnit 5 (`useJUnitPlatform()`), Spring Boot Test, Testcontainers PostgreSQL.
- Place tests in `src/test/java` and name them `*Test` or `*Tests` (both patterns already exist).
- Add/adjust tests with every behavior change, especially repository logic and DB migrations.
- Before opening a PR, run tests for each affected service.

## Commit & Pull Request Guidelines
- Prefer concise, imperative commit messages. Existing history uses both conventional style (`fix(catalog): ...`, `chore: ...`) and chapter-oriented messages (`chapter07 ...`); either is acceptable if clear.
- Keep commits focused to one logical change.
- PRs should include: purpose, impacted modules, validation commands run, and any migration/config impact.

## Configuration & Security Notes
- Do not commit secrets. Use environment variables such as `GH_USERNAME` and `GHCR_TOKEN` for image publishing.
- Validate Kubernetes manifests before merge (CI uses `kubeconform --strict k8s`).
