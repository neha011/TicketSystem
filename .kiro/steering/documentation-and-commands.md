---
inclusion: always
---

# Documentation Skills & Commands

## Code Documentation
- Public classes and methods get a Javadoc comment explaining *why*/*what*, not a restatement of the method name.
- Non-obvious business rules get an inline comment explaining the reasoning, not the mechanics.
- Keep a `README.md` at project root covering: what the service does, how to run it locally, how to run tests, and key environment variables.
- API documentation is generated (springdoc-openapi/Swagger), not hand-written — see `api-standards.md`.

## Spec Documentation
- Every feature/bugfix goes through the spec workflow (`.kiro/specs/{feature-name}/`): `requirements.md` → `design.md` → `tasks.md`.
- Requirements use EARS format (WHEN/IF/WHILE/WHERE ... THE ... SHALL ...) with explicit acceptance criteria.
- Design docs capture correctness properties for any nontrivial logic — these drive the property-based tests (see `testing-guidelines.md`).
- Keep specs up to date: if implementation diverges from the design during a task, update `design.md` rather than letting it drift silently.

## Commands: Build & Run
```bash
# Build the project
mvn clean install

# Run the app locally
mvn spring-boot:run

# Run with a specific profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

## Commands: Test Generation & Execution
```bash
# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=TicketServiceTest

# Run only property-based tests (jqwik)
mvn test -Dtest=*PropertyTest

# Generate a new unit test for a class — ask Kiro directly, e.g.:
#   "Generate unit tests for TicketService covering create, update, and not-found paths"
# Generate property-based tests for an invariant — ask Kiro directly, e.g.:
#   "Write a jqwik property test asserting ticket status transitions never skip a state"
```

## Commands: Code Review
- Ask Kiro: **"Review the changes in #Git Diff for adherence to our steering guidelines"**
- Ask Kiro: **"Review `<file>` for Spring Boot best practices and security issues"**
- Ask Kiro: **"Check this controller against our API Standards steering doc"**

## Commands: Spec Review
- Ask Kiro: **"Analyze requirements for `<feature-name>"** — runs ambiguity/consistency analysis on `requirements.md`.
- Ask Kiro: **"Review `design.md` for `<feature-name>` against the requirements"**
- Ask Kiro: **"Check tasks.md for `<feature-name>` for missing dependencies or gaps"**

## Commands: Static Analysis (recommended, add if not present)
```bash
# Checkstyle (code style)
mvn checkstyle:check

# SpotBugs (bug patterns)
mvn spotbugs:check

# Dependency vulnerability check
mvn org.owasp:dependency-check-maven:check
```
