---
inclusion: always
---

# Java / Spring Boot Guidelines

## Project Setup
- Build tool: **Maven** (`pom.xml` at project root). Use exact/pinned dependency versions, managed via `<dependencyManagement>` or Spring Boot's parent BOM.
- Java version: 17+ (LTS). Use records, sealed classes, and pattern matching where they simplify code.
- Package structure: `com.<org>.<service>.<layer>` (e.g. `com.ticketsystem.ticket.controller`, `.service`, `.repository`, `.domain`, `.dto`, `.config`, `.exception`).

## Layering & Responsibilities
- **Controller**: HTTP concerns only — request mapping, validation trigger, status codes. No business logic.
- **Service**: Business logic lives here. Services depend on repository interfaces, not implementations.
- **Repository**: Spring Data JPA interfaces for persistence. No business logic.
- **DTOs**: Never expose JPA entities directly in API responses/requests. Map entities <-> DTOs explicitly (MapStruct or manual mappers).
- Keep controllers thin; push logic down to services so it's unit-testable without a web context.

## Dependency Injection
- Use constructor injection exclusively (no field `@Autowired`). Mark dependencies `private final`.
- Favor interfaces for services/repositories to keep components mockable and swappable.

## Configuration
- Externalize config via `application.yml` (prefer YAML over `.properties`). Use Spring profiles (`dev`, `test`, `prod`) for environment-specific values.
- Bind grouped config with `@ConfigurationProperties` classes rather than scattering `@Value` injections.
- Never commit secrets (DB passwords, API keys) to `application.yml`. Use environment variables or a secrets manager; reference them with `${ENV_VAR}` placeholders.

## Error Handling
- Use `@RestControllerAdvice` with `@ExceptionHandler` for centralized error handling.
- Define a custom exception hierarchy (e.g. `NotFoundException`, `ValidationException`, `ConflictException`) mapped to appropriate HTTP status codes.
- Return a consistent error response shape (see API Standards steering doc) — never leak stack traces or internal exception messages to clients.

## Validation
- Use `jakarta.validation` annotations (`@NotNull`, `@Size`, `@Valid`, etc.) on request DTOs.
- Validate at the controller boundary (`@Valid @RequestBody`). Do not re-validate the same rules deeper in the service layer unless the invariant differs from input validation.

## Persistence
- Use Spring Data JPA repositories. Avoid `EntityManager` boilerplate unless a query genuinely needs it.
- Prefer explicit `@Query` (JPQL) over deeply nested derived query method names once they get hard to read.
- Use database migrations (Flyway or Liquibase) for schema changes — never rely on `ddl-auto: update` outside local dev.
- Wrap multi-step writes in `@Transactional` at the service layer, not the controller.

## Logging
- Use SLF4J (`LoggerFactory.getLogger(...)`), never `System.out.println`.
- Log at appropriate levels: `ERROR` for failures needing attention, `WARN` for recoverable issues, `INFO` for key business events, `DEBUG` for diagnostic detail.
- Never log secrets, tokens, or full PII (mask/redact where needed).

## Code Style
- Follow standard Java conventions (Google Java Style or equivalent). Keep methods short and single-purpose.
- Favor immutability: `final` fields, records for simple data carriers.
- Avoid checked-exception-heavy APIs in service signatures where unchecked domain exceptions are clearer.
