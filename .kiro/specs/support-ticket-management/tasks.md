# Implementation Plan: Support Ticket Management

## Overview

Bottom-up implementation of the Spring Boot 3.3 / Java 21 backend followed by the React/Next.js frontend. Each layer is completed and independently verifiable before the layer above it depends on it: scaffolding → domain enums and the pure `TicketStatusTransitionValidator` → entities and Flyway migration → repositories and Specifications → DTOs, `@TrimmedSize`, mappers → services → controllers, `GlobalExceptionHandler`, security → OpenAPI → frontend → integration tests.

The ticket status state machine is the highest-risk logic in the system, so it is built first (with no dependencies on Spring, JPA, or HTTP) and tested at three levels: exhaustive jqwik property tests over the pure validator, service-level property tests with a mocked repository, and integration tests driving real HTTP against a real PostgreSQL container.

Property numbers below refer to the **final `## Correctness Properties` section of `design.md`** (Properties 1–25, grouped State Machine / Validation / Mapping and Persistence / Search, Filter, Pagination / Frontend). `design.md` also contains an earlier, coarser 19-property block; the 25-property numbering is used here because it maps one-to-one onto the layers of this plan. Reconciling the two blocks in `design.md` is worth doing but is out of scope for this plan.

## Tasks

- [x] 1. Project scaffolding and shared foundations
  - [x] 1.1 Create the Maven project skeleton with pinned dependencies
    - Create root `pom.xml` using `spring-boot-starter-parent` 3.3.5, Java 21
    - Declare `spring-boot-starter-web`, `-validation`, `-data-jpa`, `postgresql`, `h2`, `flyway-core`, `flyway-database-postgresql`, `springdoc-openapi-starter-webmvc-ui` 2.6.0
    - Declare test scope: `spring-boot-starter-test`, `net.jqwik:jqwik` 1.9.1, `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`
    - Create the `com.ticketsystem.ticket` package tree (`controller`, `service`, `repository`, `domain`, `dto`, `validation`, `config`, `exception`) and `TicketServiceApplication`
    - Add `README.md` covering what the service does, how to run it locally, how to run tests, and the required environment variables
    - _Requirements: 9.4, 10.1_

  - [x] 1.2 Configure `application.yml` profiles and persistence settings
    - `local`: H2 in-memory, `ddl-auto: update` permitted, Flyway disabled
    - `test` / `prod`: Flyway enabled, `ddl-auto: validate`, `baseline-on-migrate: false`, `validate-on-migrate: true`
    - `spring.jpa.open-in-view: false`; datasource credentials only as `${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}` placeholders
    - _Requirements: 9.4, 9.5, 9.6, 9.7_

  - [x] 1.3 Implement the exception hierarchy and error response DTOs
    - Abstract `ApiException` declaring its own `HttpStatus` and client-safe message, plus `NotFoundException` (404), `ValidationException` (400), `ConflictException` (409), `UnprocessableEntityException` (422)
    - `ErrorResponse` and `FieldError` records matching the single documented error shape (`timestamp`, `status`, `error`, `message`, `path`, optional `fieldErrors`)
    - _Requirements: 1.8, 3.2, 4.4, 4.5, 4.6, 5.6, 8.3, 10.2, 11.8_

  - [x] 1.4 Write profile configuration smoke tests
    - Assert Flyway is enabled and `ddl-auto` is `validate` under a non-local profile, and that automatic generation is permitted under `local`
    - _Requirements: 9.4, 9.6_

- [x] 2. Domain enums and the pure status transition validator
  - [x] 2.1 Implement `TicketStatus` and `TicketPriority` enums with the allowed-transition table
    - `TicketStatus` holds the static `ALLOWED` map exactly as designed: OPEN→{IN_PROGRESS, CANCELLED}, IN_PROGRESS→{RESOLVED, CANCELLED}, RESOLVED→{CLOSED}, CLOSED→{}, CANCELLED→{}
    - Expose `allowedTargets()` and `isTerminal()`; no state contains itself, so same-status rejection falls out of the table
    - `TicketPriority`: LOW, MEDIUM, HIGH, CRITICAL
    - _Requirements: 8.1, 8.5, 8.6_

  - [x] 2.2 Implement `TicketStatusTransitionValidator` as pure, dependency-free logic
    - `isPermitted(from, to)`, `permittedTargets(from)`, `requirePermitted(from, to)`
    - `requirePermitted` throws `ConflictException` whose message names both the current and the requested status, so the UI never invents the text
    - No Spring annotations, no constructor arguments, no I/O
    - _Requirements: 8.1, 8.3, 8.4, 8.6, 8.7_

  - [x] 2.3 Write property test for the full 25-pair transition space
    - **Property 1: Transition permitted if and only if in the allowed table**
    - jqwik `@Property` in `TicketStatusTransitionValidatorPropertyTest`, enumerating the entire `TicketStatus × TicketStatus` cross product (5 permitted, 20 rejected) rather than sampling; assert the biconditional, not two separate implications
    - **Validates: Requirements 8.1, 8.4**

  - [x] 2.4 Write property test for the terminal-state invariant
    - **Property 2: No transition out of a terminal state is ever permitted**
    - For all targets and for `terminal ∈ {CLOSED, CANCELLED}`, `isPermitted(terminal, target)` is false and `terminal.allowedTargets()` is empty
    - **Validates: Requirements 8.6**

  - [x] 2.5 Write property test for the never-return-to-OPEN invariant
    - **Property 3: OPEN is unreachable after creation**
    - For all `current` in `TicketStatus`, `isPermitted(current, OPEN)` is false — covers the RESOLVED/CLOSED/CANCELLED→OPEN cases and every other source state
    - **Validates: Requirements 8.5**

  - [x] 2.6 Write property test for the no-skipping invariant over multi-step paths
    - **Property 4: Reachable status sequences never skip a required state**
    - Generate transition sequences of length 0..12 from OPEN, keep only permitted steps, assert: RESOLVED implies IN_PROGRESS appeared earlier, CLOSED implies IN_PROGRESS then RESOLVED appeared earlier; assert no single step realizes OPEN→RESOLVED, OPEN→CLOSED, or IN_PROGRESS→CLOSED
    - **Validates: Requirements 8.1, 8.5, 8.6**

  - [x] 2.7 Write property test for rejection message content
    - **Property 8: Rejection messages name both the current and requested status**
    - For all non-permitted pairs, the `ConflictException` message contains both status names
    - **Validates: Requirements 8.7**

  - [x] 2.8 Write unit tests for the validator's named examples
    - JUnit 5 + AssertJ: each of the 5 permitted transitions accepted; each of the 20 rejected pairs listed explicitly and asserted rejected; all five self-transitions rejected
    - _Requirements: 8.1, 8.3, 8.4, 8.5, 8.6_

- [x] 3. Checkpoint - state machine logic verified in isolation
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Entities and Flyway migration
  - [x] 4.1 Implement the `Ticket` and `Comment` JPA entities
    - `Ticket`: UUID id, title (200), description (5000), `@Enumerated(STRING)` status and priority, assignee (100, nullable), `createdAt`/`updatedAt` as `Instant`, `@Version long version`
    - `@OneToMany(mappedBy = "ticket", cascade = ALL, orphanRemoval = true)` with `@OrderBy("createdAt ASC, id ASC")`
    - `Comment`: UUID id, lazy `@ManyToOne` ticket (non-optional), author (100), content (5000), `createdAt`
    - _Requirements: 1.1, 1.2, 4.1, 4.6, 8.9, 9.1, 9.3_

  - [x] 4.2 Write the V1 Flyway migration
    - `db/migration/V1__create_ticket_and_comment.sql` with both tables, FK with `ON DELETE CASCADE`, status/priority CHECK constraints, `btrim` length CHECK constraints as a defense-in-depth backstop, and the `ix_ticket_status_created` / `ix_comment_ticket_created` indexes
    - _Requirements: 9.1, 9.3, 9.4_

  - [x] 4.3 Write `@DataJpaTest` tests for entity persistence and comment association
    - Persist a ticket with comments, flush and clear the `EntityManager`, re-read and assert field fidelity, identifiers, and the ticket-comment association
    - Assert comments come back ordered by `(createdAt, id)` including timestamp ties, and that orphan removal / cascade behaves as mapped
    - _Requirements: 3.6, 9.1, 9.3_

- [ ] 5. Repositories and query specifications
  - [x] 5.1 Create `TicketRepository` and `CommentRepository`
    - `TicketRepository extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket>`; `CommentRepository` with a ticket-scoped finder ordered by `(createdAt, id)`
    - _Requirements: 2.1, 3.1, 5.1, 9.1_

  - [x] 5.2 Implement `TicketSpecifications` for keyword search and status filter
    - `keywordMatches(String)` as `lower(field) like lower(concat('%', :kw, '%'))` on title OR description, with `%` and `_` in the keyword escaped so a user wildcard cannot broaden the match; bound parameters only, never concatenated JPQL
    - `hasStatus(TicketStatus)` returning a no-op when the status is absent; compose with `Specification.allOf(...)` dropping nulls
    - Default sort `createdAt DESC, id DESC` so ordering is total
    - _Requirements: 2.2, 6.1, 6.5, 7.1_

  - [x] 5.3 Write property test for keyword case-invariance
    - **Property 17: Keyword search results are invariant under case changes of the search term**
    - `@DataJpaTest` + jqwik: for a generated corpus and a keyword of length 1..200, the result set for the keyword equals the result sets for its lowercase, uppercase, and case-flipped variants
    - **Validates: Requirements 6.1**

  - [x] 5.4 Write property test for filter equality and filter composition
    - **Property 18: Filter results equal the reference predicate, and combining filters yields the intersection**
    - Generate a corpus with randomly assigned statuses; run keyword-only, status-only, and combined queries and compare result-id sets against reference predicates; assert combined equals the intersection and that no returned ticket carries a status outside the filter
    - **Validates: Requirements 6.1, 6.2, 6.5, 7.1, 7.2, 7.5**

  - [x] 5.5 Write `@DataJpaTest` example tests for specification edge cases
    - Keyword containing `%`, `_`, and quote characters is matched literally; a non-matching keyword and a non-matching status each return an empty result; status match is exact and case-sensitive
    - _Requirements: 6.1, 6.2, 7.1, 7.2_

- [x] 6. DTOs, trimmed-length validation, and mappers
  - [x] 6.1 Implement the `@TrimmedSize` constraint and its validator
    - `@TrimmedSize(min, max)` measuring length after trimming every `Character.isWhitespace` or `Character.isSpaceChar` code point (wider than `String.strip()`, which keeps NBSP) so whitespace-only input is zero-length; the verdict is a function of the raw submitted value, with no trimming or sanitizing deserializer registered ahead of it
    - _Requirements: 1.3, 1.4, 4.3, 5.4, 5.5, 10.1, 10.4_

  - [x] 6.2 Write property test for whitespace-only rejection
    - **Property 9: Trimmed-length validation treats whitespace-only input as zero-length**
    - jqwik generator drawing from a deliberately wide whitespace set (space, tab, CR, LF, form feed, vertical tab, NBSP U+00A0, ideographic space U+3000, Ogham space mark U+1680) at lengths 0..300; assert effective length 0 and rejection for both ticket title and comment content, judged on the raw value
    - **Validates: Requirements 1.3, 5.4, 5.8, 10.4**

  - [x] 6.3 Implement request DTOs with validation annotations
    - `CreateTicketRequest` (`@TrimmedSize(min=1,max=200)` title, `@Size(max=5000)` description, `@NotNull` priority, `@Size(max=100)` assignee)
    - `UpdateTicketRequest` distinguishing absent from explicit-null for `assignee`, with a required `version`
    - `StatusTransitionRequest` (`@NotNull` status, required `version`), `CreateCommentRequest` (`@NotNull` + `@TrimmedSize(min=1,max=5000)` content, author never accepted from the body)
    - `TicketQueryParams` with `page >= 0`, `size` in 1..100, `keyword` length 1..200 when present
    - _Requirements: 1.3, 1.4, 1.5, 2.4, 4.3, 5.3, 5.5, 6.4, 10.1_

  - [x] 6.4 Implement response DTOs
    - `TicketSummaryResponse`, `TicketDetailResponse`, `CommentResponse`, `PagedResponse<T>` carrying `content`, `page`, `size`, `totalElements`, `totalPages`
    - _Requirements: 1.6, 2.3, 2.6, 3.1, 5.2, 9.8_

  - [x] 6.5 Implement `TicketMapper` and `CommentMapper`
    - Explicit hand-written entity ↔ DTO conversion; entities never leave the service layer
    - _Requirements: 1.6, 2.6, 3.1, 5.2_

  - [x] 6.6 Write property test for mapping fidelity
    - **Property 15: Entity ↔ DTO mapping preserves all field values**
    - jqwik generators producing unicode text (emoji, surrogate pairs, RTL, combining marks), boundary-length titles (exactly 1 and exactly 200 trimmed characters), all enum values, null and present assignee, and 0..20 comments; assert every field and the ticket-comment association survives entity → DTO → entity
    - **Validates: Requirements 1.6, 2.6, 3.1, 5.2, 9.3**

  - [x] 6.7 Configure Jackson for strict enums and ISO-8601 timestamps
    - `JacksonConfig` disabling `READ_UNKNOWN_ENUM_VALUES_AS_NULL` and `READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE` so an undefined enum is a 400 rather than a coerced null, disabling `WRITE_DATES_AS_TIMESTAMPS`, and leaving `ACCEPT_EMPTY_STRING_AS_NULL` disabled so whitespace-only input reaches the validator intact
    - _Requirements: 1.5, 5.8, 8.8_

- [x] 7. Checkpoint - persistence and validation building blocks verified
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Ticket creation, retrieval, and list query service
  - [x] 8.1 Implement `TicketAuthorizationService` and assignee existence checking
    - `requireCanView` / `requireCanModify` / `requireCanComment` throwing a 403-mapped exception, invoked from services rather than controllers so every entry point inherits enforcement
    - `AssigneeValidator` / `UserDirectory` resolving an assignee identifier to an existing user, raising `UnprocessableEntityException` (422) when it names no one
    - _Requirements: 3.5, 4.5_

  - [x] 8.2 Implement `TicketServiceImpl` create and `getById`
    - `create` sets status OPEN, assigns the identifier and `createdAt`/`updatedAt`, and persists inside a `@Transactional` boundary; `getById` is `@Transactional(readOnly = true)`, loads the ticket with comments, and authorizes after load but before returning
    - _Requirements: 1.1, 1.2, 3.1, 3.5, 9.1_

  - [x] 8.3 Implement `TicketServiceImpl` list with search, filter, and pagination
    - Defaults page 0 / size 20; compose `TicketSpecifications`; build `PagedResponse` metadata; treat absent, null, and empty-string status as unfiltered; reject an unrecognized status reaching the service and return zero tickets
    - _Requirements: 2.1, 2.2, 2.3, 2.5, 6.1, 6.2, 6.5, 7.1, 7.2, 7.4, 7.5, 9.8_

  - [x] 8.4 Write property test for pagination
    - **Property 19: Pagination bounds page size and keeps totals page-independent**
    - jqwik over corpus sizes (including 0 and sizes that divide the page size exactly), pages ≥ 0, sizes 1..100: content never exceeds `size`, `totalElements` is page-independent, `totalPages == ceil(totalElements / size)`, a page at or beyond `totalPages` returns empty content with coherent metadata, and concatenating all pages reproduces the full result set with no duplicates or omissions
    - **Validates: Requirements 2.2, 2.3, 2.5, 9.8**

  - [x] 8.5 Write unit tests for create and retrieval paths
    - Mockito + AssertJ: created ticket is OPEN with both timestamps set; list defaults applied when page/size absent; not-found lookup raises the 404-mapped exception; unauthorized view raises the 403-mapped exception; a repository persistence failure propagates as a failure rather than a reported success
    - _Requirements: 1.1, 1.2, 1.8, 2.1, 3.2, 3.5_

- [x] 9. Ticket field update with optimistic locking
  - [x] 9.1 Implement `TicketServiceImpl` update
    - Partial update over `title`, `description`, `priority`, `assignee`; status is not updatable here so the state machine cannot be bypassed
    - Order of checks: load → authorize → compare supplied `version` against stored (409 on mismatch) → resolve assignee (422 if unknown) → apply fields → touch `updatedAt` → save, all inside one transaction so nothing is written unless every check passes
    - Explicit-null `assignee` clears the value; omitted `assignee` leaves it untouched
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 10.5_

  - [x] 9.2 Write property test for partial-update semantics
    - **Property 16: Partial updates change exactly the supplied fields**
    - jqwik over arbitrary non-empty subsets of `{title, description, priority, assignee}` with valid values: patched fields take the new values, omitted fields retain previous values, `updatedAt` strictly advances, `status` and `createdAt` are unchanged
    - **Validates: Requirements 4.1, 4.2**

  - [x] 9.3 Write property test for version mismatch handling
    - **Property 14: Version mismatch is rejected as a conflict**
    - jqwik over `(storedVersion, suppliedVersion)` pairs: success only when equal; otherwise `ConflictException` (409) and the ticket is unchanged, for both the update and the status-transition paths
    - **Validates: Requirements 4.6, 8.9**

  - [x] 9.4 Write unit tests for update failure paths
    - Unknown ticket id raises the 404-mapped exception with no records touched; unknown assignee raises the 422-mapped exception; a mix of valid and invalid fields leaves the entity unmodified and issues no save
    - _Requirements: 4.2, 4.4, 4.5, 10.5_

- [x] 10. Status transition service
  - [x] 10.1 Implement `TicketStatusTransitionService`
    - Load → authorize → version check → `TicketStatusTransitionValidator.requirePermitted` → set status and touch `updatedAt` → save; the validator is consulted before the entity is mutated so a rejected transition never dirties it
    - Signature takes `TicketStatus`, not `String`, so an undefined value is unrepresentable at this layer
    - `@Transactional`; an `ObjectOptimisticLockingFailureException` on flush surfaces as 409
    - _Requirements: 8.2, 8.3, 8.4, 8.5, 8.6, 8.9_

  - [x] 10.2 Write property test that rejected transitions mutate nothing
    - **Property 5: A rejected transition leaves the ticket unchanged**
    - jqwik with a mocked repository over all non-permitted `(current, target)` pairs: `ConflictException` is thrown, `status`, `updatedAt`, and `version` are identical to their pre-invocation values, and `verify(repository, never()).save(any())`
    - **Validates: Requirements 8.3, 8.4, 8.5, 8.6**

  - [x] 10.3 Write property test that permitted transitions apply exactly the requested status
    - **Property 6: A permitted transition applies exactly the requested status**
    - For every source with at least one permitted target and every such target: status becomes exactly the target, `updatedAt` advances, all other fields are unchanged, and the response reports the new status
    - **Validates: Requirements 8.2**

  - [x] 10.4 Write unit tests for named transition rejections and error paths
    - Each source status → OPEN rejected (Req 8.5); transition against a missing ticket → 404; unauthorized actor → 403; simulated `ObjectOptimisticLockingFailureException` → 409
    - _Requirements: 3.5, 4.4, 8.5, 8.9_

- [x] 11. Comment service
  - [x] 11.1 Implement `CommentServiceImpl`
    - Verify the ticket exists (404 otherwise), authorize, derive `author` from the authenticated principal rather than the request body, set `createdAt`, persist inside a transaction, and store the content trimmed with no sanitization ahead of validation
    - Ticket-scoped reads return comments ordered oldest to newest by `(createdAt, id)`
    - _Requirements: 3.6, 5.1, 5.6, 5.8, 9.1_

  - [x] 11.2 Write unit tests for the comment service
    - Happy path creates and associates the comment with a timestamp and principal-derived author; missing ticket raises the 404-mapped exception; whitespace-only content never reaches persistence
    - _Requirements: 5.1, 5.6, 5.8_

- [x] 12. Checkpoint - service layer verified
  - Ensure all tests pass, ask the user if questions arise.

- [x] 13. Controllers, centralized exception handling, and security
  - [x] 13.1 Implement `TicketController`
    - `POST /api/v1/tickets` → 201 with `Location`; `GET /api/v1/tickets` with `page`/`size`/`keyword`/`status`; `GET /api/v1/tickets/{id}`; `PATCH /api/v1/tickets/{id}`; `PATCH /api/v1/tickets/{id}/status`
    - `@Valid` on bodies, `@Validated` on query params; no business logic, no transition decisions
    - _Requirements: 1.6, 2.1, 2.4, 3.1, 3.3, 4.1, 8.2_

  - [x] 13.2 Implement `CommentController`
    - `POST /api/v1/tickets/{id}/comments` → 201 with `Location: /api/v1/tickets/{ticketId}/comments/{commentId}`; ticket-scoped comment read
    - _Requirements: 3.1, 3.6, 5.2, 5.6_

  - [x] 13.3 Implement `GlobalExceptionHandler`
    - `@RestControllerAdvice` mapping `ApiException` subclasses by their declared status plus `MethodArgumentNotValidException` and `ConstraintViolationException` → 400 with a `fieldErrors` entry for every failing field, `HttpMessageNotReadableException` → 400 (malformed JSON, undefined enum), `MethodArgumentTypeMismatchException` → 400 (malformed UUID, unknown status query enum), `ObjectOptimisticLockingFailureException` → 409, `AccessDeniedException` → 403, `DataAccessException` and `Exception` → 500 with a constant generic message
    - Internal detail is logged at ERROR and never placed in a response body
    - _Requirements: 1.8, 2.4, 3.2, 3.3, 4.3, 4.4, 4.5, 4.6, 4.10, 5.3, 5.4, 5.5, 5.6, 6.4, 7.3, 8.3, 8.8, 8.9, 9.2, 10.2, 10.3, 11.8_

  - [x] 13.4 Implement `SecurityConfig` with default deny
    - `anyRequest().authenticated()` as the closing rule; only `/actuator/health`, `/actuator/info`, and (non-production profiles only) the OpenAPI routes are explicitly public
    - `AuthenticationEntryPoint` → 401 and `AccessDeniedHandler` → 403, both writing the standard `ErrorResponse` shape with no ticket data, comments, or other ticket-related fields
    - Stateless session policy
    - _Requirements: 3.4, 3.5_

  - [x] 13.5 Write property test separating undefined target status from illegal transition
    - **Property 7: Undefined target status values are rejected as validation failures, never as conflicts**
    - jqwik + `@WebMvcTest`/MockMvc over strings that are not exact `TicketStatus` names (including `"open"`, `"Open"`, `" OPEN"`): response is 400, never 409, and the ticket's status is unchanged
    - **Validates: Requirements 8.8**

  - [x] 13.6 Write property test that reported field errors equal the invalid field set
    - **Property 10: Reported field errors are exactly the set of invalid fields**
    - Generate a validity vector over `(title, description, priority, assignee)` for create, update, and comment requests, forcing runs with two, three, and four simultaneously invalid fields; assert set equality between `fieldErrors` field names and the expected failing set, and that each entry carries a non-empty reason
    - **Validates: Requirements 1.4, 1.5, 4.3, 4.9, 4.10, 10.1, 10.2**

  - [x] 13.7 Write property test for unparseable request bodies
    - **Property 12: Unparseable request bodies are rejected without side effects**
    - Generate malformed JSON (truncated objects, mismatched types, trailing content, unbalanced delimiters, `NaN` literals) against all three write endpoints; assert 400 every time and never 500
    - **Validates: Requirements 10.3**

  - [x] 13.8 Write property test for fail-closed query parameter rejection
    - **Property 20: Out-of-range and unrecognized query parameters are rejected without returning data**
    - Generate negative pages including `Integer.MIN_VALUE`, sizes ≤ 0 and > 100 including `Integer.MAX_VALUE`, keywords of length 0 and > 200 **planted in the corpus** so a search-then-validate implementation is caught leaking data, and non-enum status strings; assert 400 with no ticket data in the body
    - **Validates: Requirements 2.4, 6.4, 6.6, 7.3**

  - [x] 13.9 Write `@WebMvcTest` slice tests for HTTP semantics
    - 201 plus `Location` on ticket create and comment create; malformed UUID path variable → 400; 401 and 403 bodies assert on content and carry no ticket data; 409 body message names both the current and requested status; the error shape is identical across every status
    - _Requirements: 1.6, 3.3, 3.4, 3.5, 5.2, 8.7_

- [x] 14. OpenAPI documentation annotations
  - [x] 14.1 Annotate controllers and DTOs for springdoc, and add `OpenApiConfig`
    - `@Operation` plus an `@ApiResponse` entry per documented status code on every handler; `@Schema(description = ...)` on every DTO field with a non-obvious constraint (trimmed lengths, version semantics, absent-vs-null assignee)
    - Swagger UI and `/v3/api-docs` exposed in non-production profiles only
    - _Requirements: 1.6, 2.3, 3.1, 4.3, 5.2, 8.2_

  - [x] 14.2 Write a test asserting the generated OpenAPI document covers every endpoint
    - Fetch `/v3/api-docs` and assert each endpoint is present with its documented status codes, so the generated spec cannot silently drift from the code
    - _Requirements: 1.6, 2.3, 3.1, 4.3, 5.2, 8.2_

- [x] 15. Checkpoint - backend API complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 16. Frontend API client and error handling
  - [x] 16.1 Scaffold the Next.js app and data-fetching layer
    - App structure, TanStack Query provider with optimistic updates deliberately disabled so pending submissions are never rendered as confirmed values
    - _Requirements: 2.6, 4.7, 4.8_

  - [x] 16.2 Implement `apiClient`
    - `fetch` wrapper normalizing backend `ErrorResponse` bodies and network failures into a single `ApiError` shape carrying status, message, and `fieldErrors`; never surfaces raw payload text to callers
    - _Requirements: 3.4, 11.8_

  - [x] 16.3 Implement `errorMapper` as a pure status → single message table
    - 400 with a message → that message; 400 without → generic validation message; 401 → session expired and prompt to log in; 403 → not permitted; 404 → ticket not found; 409 → message naming current and requested status; 500 and network failure → fixed generic text built from a constant
    - Returns exactly one message per failed request
    - _Requirements: 8.7, 11.1, 11.2, 11.4, 11.5, 11.6, 11.7, 11.8, 11.9_

  - [x] 16.4 Write property test that a failed request yields exactly one correct message
    - **Property 21: A failed request produces exactly one status-specific message**
    - fast-check over `ApiError` values across the full status set with arbitrary, empty, blank, and absent messages; assert the returned count is exactly one and that it is the message mapped to the received status
    - **Validates: Requirements 11.4, 11.5, 11.6, 11.7, 11.9**

  - [x] 16.5 Write property test that generic failures leak nothing
    - **Property 22: Generic failures never leak internal detail**
    - fast-check with adversarial 500 and network-failure payloads built from Java stack-trace shapes, JPQL fragments, exception class names, and absolute file paths; assert the output equals the fixed generic message and contains no token from the payload
    - **Validates: Requirements 11.8**

  - [x] 16.6 Implement the form state reducer
    - Applying a 400 response adds error annotations only; entered field values are never cleared, defaulted, or reset, and state resets only on success
    - _Requirements: 11.3_

  - [x] 16.7 Write property test that validation failure preserves form input
    - **Property 23: A validation failure preserves form input**
    - fast-check over arbitrary form states (partially filled, whitespace-laden, maximum-length, special characters) paired with 400 payloads whose `fieldErrors` name both present and absent fields; assert value-level equality and that the only delta is the error map
    - **Validates: Requirements 11.3**

- [x] 17. Frontend views
  - [x] 17.1 Implement the ticket list view
    - Discriminated state (loading / error / empty / populated) so exactly one is rendered; per-row title, status, priority, assignee; "Unassigned" placeholder confined to the populated state; pagination controls, status filter, keyword search with the 1–200 length message
    - _Requirements: 2.6, 2.7, 2.8, 2.9, 6.3, 6.4, 7.1_

  - [x] 17.2 Write property test for the assignee placeholder
    - **Property 24: Assignee display shows "Unassigned" exactly when unassigned**
    - fast-check over null, empty, whitespace-only, and valid assignee values: the placeholder appears if and only if the value is absent or blank
    - **Validates: Requirements 2.7**

  - [x] 17.3 Implement the ticket detail view with comments
    - Full field display, comments sorted oldest to newest by `(createdAt, id)`, no-comments indication shown only when the collection is empty, comment form appending the new comment without a full page reload
    - _Requirements: 3.1, 3.6, 3.7, 3.8, 5.7_

  - [x] 17.4 Write property test for comment rendering order
    - **Property 25: Comments render oldest to newest**
    - fast-check over 0..50 comments in randomized input order with frequent `createdAt` ties: the rendered sequence is non-decreasing by `createdAt` and is a permutation of the input, and the no-comments indication appears if and only if the collection is empty
    - **Validates: Requirements 3.6, 3.7, 3.8, 5.7**

  - [x] 17.5 Implement create/edit forms and `StatusTransitionControl`
    - Create form displays the returned id, title, priority, status, and creation timestamp; edit form refreshes confirmed values without a full page reload and keeps showing the last backend-confirmed values while a submission is in flight
    - `StatusTransitionControl` offers only permitted targets and is hidden for terminal states as an affordance, with the server re-checking; renders the confirmation message on success and the mapped 409 message on rejection
    - _Requirements: 1.7, 4.7, 4.8, 8.7, 8.10_

  - [x] 17.6 Write React Testing Library example tests for the UI branches
    - One render per case: created-ticket field display, list row fields, empty state without an error indication, no-comments indication, updated values after confirmation, no-results indication, transition confirmation message, one 400-with-message and one 400-without-message render
    - _Requirements: 1.7, 2.6, 2.8, 2.9, 3.7, 4.7, 4.8, 6.3, 8.10, 11.1, 11.2_

- [x] 18. Integration tests against a real database
  - [x] 18.1 Set up the `@SpringBootTest` + Testcontainers PostgreSQL harness
    - Shared base class starting a PostgreSQL container, running Flyway against it so real migrations are exercised, and resetting state between test classes
    - _Requirements: 9.1, 9.4_

  - [x] 18.2 Write state machine integration tests through the real HTTP API
    - Walk the full lifecycle OPEN → IN_PROGRESS → RESOLVED → CLOSED via real HTTP calls, asserting 200 and the **persisted** status after each step
    - Walk both cancellation paths (OPEN → CANCELLED, IN_PROGRESS → CANCELLED)
    - Assert every outgoing transition from CLOSED and from CANCELLED returns 409 against the real database, confirming terminality survives the full stack and not just the pure validator
    - Assert an illegal-but-defined target returns 409 while an undefined target returns 400, through the real stack
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.8_

  - [x] 18.3 Write the concurrent transition test
    - N threads released by a `CountDownLatch` issue competing transitions on one ticket; assert exactly one 200, N−1 409s, and a single `version` increment
    - _Requirements: 4.6, 8.9_

  - [x] 18.4 Write the data-survives-restart test
    - Create tickets and comments, close and restart the application context against the same container, then assert every ticket and comment returns with identical field values, identifiers, and ticket-comment associations
    - Also assert that a database that has never held ticket data returns 200 with an empty result and valid pagination metadata
    - _Requirements: 9.1, 9.3, 9.8_

  - [x] 18.5 Write the migration-failure startup test
    - Point the context at a deliberately broken migration and assert startup aborts, no port is bound, and no request is served
    - _Requirements: 9.5, 9.7_

  - [x] 18.6 Write property test for validation atomicity against the real store
    - **Property 11: Validation is atomic — a request with any invalid field modifies no record**
    - jqwik over create, update, and comment requests containing at least one invalid field, against arbitrary pre-existing store contents; deep-snapshot every ticket and comment (including `version` and `updatedAt`) before and after by re-reading through a cleared persistence context, and assert equality
    - **Validates: Requirements 1.3, 4.3, 5.3, 5.4, 5.5, 10.5**

  - [x] 18.7 Write property test for absent and malformed identifiers
    - **Property 13: Absent ticket identifiers are rejected without side effects**
    - jqwik over well-formed-but-absent UUIDs (404) and malformed identifiers (400) across update, status-transition, and comment-create requests; assert the complete store snapshot is unchanged in every case
    - **Validates: Requirements 3.2, 3.3, 4.4, 5.6**

  - [x] 18.8 Write persistence durability and rollback integration tests
    - A change is committed before the success response is returned; a failure part-way through a multi-step write rolls back with no partially persisted data visible to subsequent reads; a persistence failure surfaces as 500 and is not reported as a successful create
    - _Requirements: 1.8, 9.1, 9.2_

- [x] 19. Final checkpoint - full stack verified
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP; core implementation tasks are never optional.
- Property numbers refer to the final `## Correctness Properties` section of `design.md` (Properties 1–25). Each property is implemented by exactly one `@Property` method, tagged with a comment in the form `// Feature: support-ticket-management, Property N: <title>`.
- jqwik runs at `tries = 25` by default, set centrally in `src/test/resources/junit-platform.properties` (`jqwik.tries.default=25`) to keep `mvn test` fast; an individual property raises it with `@Property(tries = ...)` only when its input space genuinely needs more coverage. Properties that enumerate a fixed, exhaustive space (the state-machine properties 2.3–2.7 over the 25 `TicketStatus × TicketStatus` pairs) cover it in full regardless of the try count. Frontend properties use fast-check at `numRuns: 25`.
- The state machine is covered at three levels by design: pure validator property tests (task 2), service-level property tests with a mocked repository (task 10), and real-HTTP integration tests against a real database (task 18.2, 18.3).
- Failing property seeds are recorded so a shrunk counterexample becomes a permanent regression case.
- If implementation diverges from the design during a task, update `design.md` rather than letting it drift.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "16.1"] },
    { "id": 1, "tasks": ["1.2", "1.3", "2.1", "16.2"] },
    { "id": 2, "tasks": ["1.4", "2.2", "4.2", "16.3", "16.6"] },
    { "id": 3, "tasks": ["2.3", "4.1", "6.1", "16.4", "16.7"] },
    { "id": 4, "tasks": ["2.4", "2.8", "4.3", "6.2", "6.3", "6.4", "6.7", "16.5"] },
    { "id": 5, "tasks": ["2.5", "5.1", "5.2", "6.5"] },
    { "id": 6, "tasks": ["2.6", "5.3", "6.6", "8.1"] },
    { "id": 7, "tasks": ["2.7", "5.4", "8.2"] },
    { "id": 8, "tasks": ["5.5", "8.3", "10.1", "11.1"] },
    { "id": 9, "tasks": ["8.4", "8.5", "9.1", "10.2", "11.2"] },
    { "id": 10, "tasks": ["9.2", "10.3", "13.1", "13.2", "13.3", "13.4"] },
    { "id": 11, "tasks": ["9.3", "9.4", "10.4", "13.5", "13.6", "13.7", "13.8", "13.9", "17.1", "17.3", "17.5"] },
    { "id": 12, "tasks": ["14.1", "17.2", "17.4", "17.6", "18.1"] },
    { "id": 13, "tasks": ["14.2", "18.2", "18.4", "18.5", "18.8"] },
    { "id": 14, "tasks": ["18.3", "18.6", "18.7"] }
  ]
}
```
