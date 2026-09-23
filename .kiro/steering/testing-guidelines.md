---
inclusion: always
---

# Testing Guidelines

## Stack
- **JUnit 5** as the test framework (`spring-boot-starter-test` pulls this in).
- **Mockito** for mocking collaborators (`@Mock`, `@InjectMocks`, or Mockito's BDD-style `given(...)`).
- **AssertJ** for fluent, readable assertions (`assertThat(...)`) instead of raw JUnit asserts.
- **jqwik** for property-based testing (PBT) of pure logic and invariants (add as a test-scope Maven dependency).
- **Testcontainers** for integration tests that need a real database/broker instead of mocking persistence.

## Test Types & Where They Live
- Unit tests: `src/test/java/.../<Class>Test.java` — mock all collaborators, no Spring context.
- Slice tests: `@WebMvcTest` for controllers, `@DataJpaTest` for repositories — load only the relevant Spring slice.
- Integration tests: `@SpringBootTest` (+ Testcontainers) for full-stack flows — keep these fewer and focused on critical paths.
- Property-based tests: co-located with unit tests, named `<Class>PropertyTest.java`, using jqwik's `@Property`.

## Correctness Properties & Property-Based Testing
- For any nontrivial business logic (pricing, state transitions, calculations, ordering, invariants), define explicit correctness properties before/alongside implementation — not just example-based tests.
- Common property patterns to consider:
  - **Invariant**: some condition holds for all valid inputs (e.g. a ticket's status transitions never skip a required state).
  - **Idempotence**: applying an operation twice has the same effect as once.
  - **Round-trip**: serialize/deserialize or encode/decode returns the original value.
  - **Metamorphic**: a known transformation of the input produces a predictable transformation of the output.
- Use jqwik `@Provide` generators to produce realistic domain data (avoid overly narrow generators that hide edge cases).
- Property tests complement, not replace, example-based unit tests for known edge cases and regressions.

## Mocking Conventions
- Mock only true external boundaries (repositories, HTTP clients, other services). Do not mock simple value objects or the class under test.
- Use `@ExtendWith(MockitoExtension.class)` on unit test classes instead of `MockitoAnnotations.openMocks`.
- Prefer `given(mock.method()).willReturn(...)` (BDDMockito) for readability, and verify behavior with `verify(mock).method(...)` only when the interaction itself matters.

## Coverage & Quality Bar
- New business logic requires unit tests covering: the happy path, boundary conditions, and at least one failure/error path.
- Bug fixes require a regression test that fails before the fix and passes after.
- Don't chase 100% line coverage for its own sake — prioritize tests that assert meaningful behavior over trivial getters/setters.

## Test Data & Isolation
- Tests must be independent and order-independent — no shared mutable static state between tests.
- Use test data builders or factory methods instead of duplicating object construction across tests.
- For `@DataJpaTest`/`@SpringBootTest` with a real DB, reset state between tests (e.g. `@Transactional` rollback, or Testcontainers with a fresh schema per test class).

## Naming
- Test method names describe behavior, not implementation: `shouldReturn404WhenTicketNotFound()` rather than `test1()`.
- Follow given/when/then or arrange/act/assert structure within the test body, with blank lines separating each phase.
