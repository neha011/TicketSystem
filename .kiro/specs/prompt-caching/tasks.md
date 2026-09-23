# Implementation Plan: Prompt Caching

## Overview

This plan implements the file-backed prompt cache incrementally, following the existing
`controller → service → store` layering. It builds from the pure, independently testable pieces
(normalization, key derivation, eviction, JSON round-trip) up through the concurrency-guarding store,
then the service orchestration, and finally the REST controller and integration wiring. Property-based
tests (jqwik) for the 15 design correctness properties are placed close to the code they validate so
invariant failures surface early. All new code lives under
`src/main/java/com/ticketsystem/ticket` in the appropriate layer subpackage, and tests under the
mirrored `src/test/java` path.

Each property test is tagged with a comment in the format
`Feature: prompt-caching, Property N: <property text>` and references the design property plus the
requirement clause it validates.

## Tasks

- [ ] 1. Establish configuration, domain records, DTOs, and the new exception
  - [x] 1.1 Create `ResponseParams` value object and `CacheEntry` record
    - Add `com.ticketsystem.ticket.domain.ResponseParams` holding response-affecting params (e.g. `model`), with deterministic sorted-key serialization used for canonical identity
    - Add `com.ticketsystem.ticket.domain.CacheEntry` record with `cacheKey`, `promptText`, `canonicalIdentity`, nullable `cachedResponse`, `createdAt`, `lastAccessedAt`, `hitCount`, plus copy helpers for hit-count/last-accessed updates
    - Annotate for Jackson JSON (de)serialization consistent with the shared `ObjectMapper` (JSR-310)
    - _Requirements: 1.2, 2.3, 2.4, 4.1_

  - [x] 1.2 Create `PromptCacheProperties` configuration binding
    - Add `com.ticketsystem.ticket.config.PromptCacheProperties` with `@ConfigurationProperties(prefix = "prompt-cache")` binding `file` (default `./data/prompt-cache.json`), `maxEntries` (range 1..1_000_000, default 10_000), `ttl` (range 1s..31_536_000s, default 604_800s)
    - Implement a `@PostConstruct` normalization step that clamps out-of-range/unparseable values to the documented default and logs the rejection at WARN, rather than aborting startup
    - Register the properties bean and add documented defaults to `application.yml`
    - _Requirements: 4.2, 5.1, 5.2, 5.3_

  - [ ]* 1.3 Write property test for out-of-range configuration resolution
    - **Property 13: Out-of-range configuration resolves to the default**
    - **Validates: Requirements 5.3**
    - `PromptCachePropertiesPropertyTest` generating values inside/outside the permitted ranges and unparseable values; assert effective value equals the documented default when out of range
    - _Requirements: 5.3_

  - [-] 1.4 Create request/response DTOs
    - Add `SubmitPromptRequest` (`dto/request`) with `@NotNull @TrimmedSize(min = 1, max = 100000) prompt`, optional `model`, optional `response`, using camelCase JSON and springdoc `@Schema` field descriptions
    - Add `PromptSubmissionResponse` and `CacheEntryResponse` (`dto/response`) with camelCase JSON, ISO-8601 `Instant` timestamps, and `@Schema` annotations
    - _Requirements: 1.5, 1.6, 8.1, 8.2, 8.3_

  - [x] 1.5 Add `CacheUnavailableException`
    - Add `com.ticketsystem.ticket.exception.CacheUnavailableException extends ApiException`, mapped to `HttpStatus.INTERNAL_SERVER_ERROR`, carrying only a constant client-safe message ("The prompt cache is temporarily unavailable."); no new advice needed since `GlobalExceptionHandler` already handles `ApiException`
    - _Requirements: 2.6, 6.2, 7.3_

- [x] 2. Implement pure normalization and key derivation
  - [x] 2.1 Implement `PromptNormalizer`
    - Add `PromptNormalizer` (service package) that trims leading/trailing whitespace and collapses internal whitespace runs to a single space, reusing the whitespace definition from `TrimmedSizeValidator` for consistency
    - _Requirements: 2.1, 2.2_

  - [ ]* 2.2 Write property test for normalization determinism
    - **Property 1: Normalization is deterministic**
    - **Validates: Requirements 2.1, 2.2**
    - `PromptNormalizerPropertyTest` over arbitrary strings incl. mixed whitespace/Unicode; assert repeated normalization of the same input yields identical output
    - _Requirements: 2.1, 2.2_

  - [x] 2.3 Implement `CacheKeyDeriver`
    - Add `CacheKeyDeriver` deriving a fixed-length SHA-256 key rendered as 64 lowercase hex chars from normalized text + `ResponseParams`, using the deterministic canonical identity for input
    - _Requirements: 2.4, 2.5_

  - [ ]* 2.4 Write property test for whitespace-variant equal keys
    - **Property 2: Whitespace-variant prompts derive identical keys**
    - **Validates: Requirements 1.1, 2.3**
    - `CacheKeyDeriverPropertyTest`: for text and a whitespace-perturbed variant with identical params, assert derived keys are identical
    - _Requirements: 1.1, 2.3_

  - [ ]* 2.5 Write property test for distinct identity → distinct key
    - **Property 3: Distinct canonical identities derive distinct keys**
    - **Validates: Requirements 2.4**
    - `CacheKeyDeriverPropertyTest`: for pairs of distinct canonical identities (differing normalized text or ≥1 param), assert derived keys differ
    - _Requirements: 2.4_

  - [ ]* 2.6 Write property test for fixed-length keys
    - **Property 4: Keys are fixed length**
    - **Validates: Requirements 2.5**
    - `CacheKeyDeriverPropertyTest`: over arbitrary prompts incl. empty/huge, assert every derived key has the same fixed length
    - _Requirements: 2.5_

  - [ ]* 2.7 Write unit tests for normalizer and key deriver edge cases
    - Whitespace-only input, empty string, non-breaking spaces, mixed internal runs; known example key stability
    - _Requirements: 2.1, 2.2, 2.4, 2.5_

- [x] 3. Implement pure eviction policy
  - [x] 3.1 Implement `EvictionPolicy`
    - Add `EvictionPolicy` with `expired(entries, now, ttl)` returning entries whose age (`now - createdAt`) `>= ttl`, and `overflow(entries, maxSize)` selecting removal candidates ordered by ascending last-accessed, ties broken by ascending creation timestamp, until remaining `<= maxSize`
    - _Requirements: 5.4, 5.5_

  - [ ]* 3.2 Write property test for overflow eviction ordering
    - **Property 11: Overflow eviction bounds size and respects LRU-then-oldest ordering**
    - **Validates: Requirements 5.5**
    - `EvictionPolicyPropertyTest` over generated entry sets + max sizes; assert remaining count `<= maxSize` and every evicted entry orders at/before every retained entry under the LRU-then-oldest order
    - _Requirements: 5.5_

  - [ ]* 3.3 Write property test for eviction idempotence
    - **Property 12: Eviction is idempotent**
    - **Validates: Requirements 5.7**
    - `EvictionPolicyPropertyTest`: applying eviction twice with no intervening change yields the same remaining set
    - _Requirements: 5.7_

- [x] 4. Implement JSON file persistence
  - [x] 4.1 Implement `CacheFileReader` and `CacheFileWriter`
    - Add `CacheFileReader.read()` deserializing the JSON array of `CacheEntry` (empty/newly-created file → empty set; invalid JSON raises)
    - Add `CacheFileWriter.write(Collection<CacheEntry>)` serializing via the shared Jackson `ObjectMapper`, writing to `<file>.tmp` then `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`, cleaning up the temp file on failure
    - _Requirements: 4.1, 4.5, 6.5, 7.3_

  - [ ]* 4.2 Write property test for serialization round-trip
    - **Property 9: Serialization round-trip preserves the entry set**
    - **Validates: Requirements 4.6, 1.3, 3.7, 3.8, 5.6**
    - `CacheFileRoundTripPropertyTest` generating `Set<CacheEntry>`; write then read back and assert set equality over cacheKey, promptText, cachedResponse, createdAt, lastAccessedAt, hitCount
    - _Requirements: 4.6, 1.3, 3.7, 3.8, 5.6_

  - [ ]* 4.3 Write unit tests for reader/writer edge cases
    - Empty file and newly-created file both yield an empty set; invalid JSON raises; writer leaves no temp/partial file on injected failure
    - _Requirements: 4.5, 7.3_

- [ ] 5. Implement the concurrency-guarding `CacheStore`
  - [-] 5.1 Implement `CacheStore` with in-memory map, read/write lock, and startup load
    - Add `CacheStore` interface + implementation backed by `HashMap<String, CacheEntry>` guarded by a `ReentrantReadWriteLock`; `get`, `snapshot`, and `mutateAndPersist(Consumer<Map<...>>)`
    - `mutateAndPersist` acquires the write lock with a 5s timeout (throw `CacheUnavailableException` on timeout, leaving state untouched), mutates the map, delegates to `CacheFileWriter`, and rolls back the in-memory mutation from a pre-mutation snapshot if the write fails
    - `@PostConstruct init()` ensures parent dirs + `Cache_File` exist, loads via `CacheFileReader`; on unreadable/invalid JSON, log WARN and start with an empty cache
    - _Requirements: 4.3, 4.4, 6.1, 6.2, 6.5, 7.1, 7.2, 7.3_

  - [ ]* 5.2 Write unit tests for store startup and write-failure behavior
    - Missing file/dirs are created; empty/newly-created file → empty set; corrupt JSON → empty cache + WARN, still accepts mutations; injected writer failure → rollback, no partial file, `CacheUnavailableException`; write-lock held elsewhere → 5s timeout → `CacheUnavailableException`, file unchanged
    - _Requirements: 4.3, 4.4, 4.5, 6.2, 7.1, 7.2, 7.3_

- [~] 6. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 7. Implement `PromptCacheService` orchestration
  - [~] 7.1 Implement `PromptCacheService.submit`
    - Add `PromptCacheService` interface + implementation wiring `PromptNormalizer`, `CacheKeyDeriver`, `EvictionPolicy`, `CacheStore`, `PromptCacheProperties`, and the injected `Clock`
    - Normalize → reject empty-after-normalize (`ValidationException`) and over-length (validation) → derive key → detect collision (existing entry with differing canonical identity → `CacheUnavailableException`, leave entry unchanged) → under `mutateAndPersist`: on miss apply TTL+overflow eviction, create entry (hitCount 0, equal created/last-accessed), persist, report miss; on non-expired hit increment hitCount by one, set last-accessed to now, persist, report hit with stored response iff present; on expired hit remove and follow miss path
    - Reject a supplied response for a non-matching key with `ValidationException` without persisting
    - _Requirements: 1.2, 1.4, 1.5, 1.6, 2.6, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 5.4, 5.5_

  - [~] 7.2 Implement `PromptCacheService.findByKey`
    - Look up by key; treat an entry whose age `>= ttl` as a miss and remove it (persist), returning empty so the controller answers 404
    - _Requirements: 3.1, 5.4, 8.4_

  - [ ]* 7.3 Write property test for collision detection
    - **Property 5: Collision detection rejects and preserves the existing entry**
    - **Validates: Requirements 2.6**
    - `PromptCacheServicePropertyTest` with an injected key-deriver seam forcing two distinct identities to the same key; assert second submit rejected with key-derivation error (no internal detail) and existing entry unchanged
    - _Requirements: 2.6_

  - [ ]* 7.4 Write property test for empty-after-normalization rejection
    - **Property 6: Empty-after-normalization submissions are rejected without side effect**
    - **Validates: Requirements 1.5**
    - `PromptCacheServicePropertyTest` over whitespace-only strings; assert validation error and cache unchanged (no entry created/persisted)
    - _Requirements: 1.5_

  - [ ]* 7.5 Write property test for miss creating exactly one entry
    - **Property 7: A miss creates exactly one entry and reports a miss**
    - **Validates: Requirements 1.2, 3.4**
    - `PromptCacheServicePropertyTest`: valid prompt vs empty store; assert reported miss, exactly one entry for the key with hitCount 0 and equal created/last-accessed timestamps
    - _Requirements: 1.2, 3.4_

  - [ ]* 7.6 Write property test for hit update semantics
    - **Property 8: A hit updates access metadata, never duplicates, and returns the stored response iff present**
    - **Validates: Requirements 1.4, 3.2, 3.3, 3.5, 3.6**
    - `PromptCacheServicePropertyTest` with a pinned `Clock`, prompts with/without stored response; assert reported hit, hitCount +1, last-accessed set to submission time, single entry retained, stored response returned iff present
    - _Requirements: 1.4, 3.2, 3.3, 3.5, 3.6_

  - [ ]* 7.7 Write property test for expiry treated as miss and removal
    - **Property 10: Expired entries are treated as misses and removed**
    - **Validates: Requirements 5.4**
    - `PromptCacheServicePropertyTest` with a fixed clock generating ages/TTLs; assert an entry with age `>= ttl` yields a miss and is removed
    - _Requirements: 5.4_

  - [ ]* 7.8 Write unit tests for service failure and boundary paths
    - Length boundary 100000 accepted / 100001 rejected; response-for-missing-key rejected without persist; hit with no stored response returns explicit no-response indication
    - _Requirements: 1.6, 3.3, 3.9_

- [~] 8. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 9. Implement `PromptCacheController` and wire the REST API
  - [~] 9.1 Implement `PromptCacheController`
    - Add controller under `/api/v1/prompt-cache`: `POST` returns 200 `PromptSubmissionResponse` on hit, 201 with `Location: /api/v1/prompt-cache/{cacheKey}` on new entry; `GET /{cacheKey}` returns 200 `CacheEntryResponse` or 404 (via `findByKey` returning empty → `NotFoundException`)
    - `@Valid @RequestBody` trigger only; delegate all policy to the service; add springdoc `@Operation`/`@ApiResponse`/`@Schema` documenting request body, every response status, and each response schema
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.7_

  - [ ]* 9.2 Write `@WebMvcTest` slice tests for the controller
    - 200 hit body, 201 + `Location` for new entry, 200 `GET` body, 404 miss, 400 for malformed/missing/blank-after-normalize/over-long; assert camelCase JSON and standard error shape
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

- [ ] 10. Integration wiring and end-to-end verification
  - [ ]* 10.1 Write property test for concurrent same-key submissions
    - **Property 14: Concurrent same-key submissions retain one entry and sum the hits**
    - **Validates: Requirements 6.3**
    - `PromptCacheConcurrencyPropertyTest` running N concurrent identical submits against a temp `Cache_File`; assert exactly one entry for the key and hitCount increased by exactly N
    - _Requirements: 6.3_

  - [ ]* 10.2 Write property test for concurrent distinct-key submissions
    - **Property 15: Concurrent distinct-key submissions lose no entry**
    - **Validates: Requirements 6.4**
    - `PromptCacheConcurrencyPropertyTest` running concurrent distinct-key submits (total ≤ max) against a temp `Cache_File`; assert one entry per distinct key with none lost
    - _Requirements: 6.4_

  - [ ]* 10.3 Write `@SpringBootTest` integration tests with a temp `Cache_File`
    - Startup creates the file/dirs; corrupt JSON at startup → empty cache + WARN, still serves submits; persisted entry survives a reload (round-trip through the running app); config defaults applied when absent (default path, 10_000 max, 7-day TTL)
    - _Requirements: 4.2, 4.3, 4.4, 5.1, 5.2, 7.1, 7.2_

  - [ ]* 10.4 Write springdoc OpenAPI documentation smoke test
    - `@SpringBootTest` hitting `/v3/api-docs`; assert each prompt-cache endpoint documents its request body schema, every response status code, and each response body schema
    - _Requirements: 8.7_

- [~] 11. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP, but they encode the design's correctness properties and coverage bar and should be implemented for production confidence.
- Each task references specific requirement clauses (not just user stories) for traceability, and each property test references the exact design property it validates.
- Property tests use jqwik with ≥ 100 iterations and `@Provide` generators; a pinned `Clock` (overriding `ClockConfig`) makes TTL/timestamp properties deterministic.
- Req 8.6 (p95 latency ≤ 500ms under 50 concurrent requests) is a performance target verified by a load test, not an automated logic test, and is intentionally out of scope for these tasks.
- The store plays the repository role for this feature; no JPA is used because the requirements mandate a JSON file on the filesystem.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.5", "2.1", "2.3", "3.1", "4.1"] },
    { "id": 1, "tasks": ["1.3", "1.4", "2.2", "2.4", "2.5", "2.6", "2.7", "3.2", "3.3", "4.2", "4.3", "5.1"] },
    { "id": 2, "tasks": ["5.2", "7.1", "7.2"] },
    { "id": 3, "tasks": ["7.3", "7.4", "7.5", "7.6", "7.7", "7.8", "9.1"] },
    { "id": 4, "tasks": ["9.2", "10.1", "10.2", "10.3", "10.4"] }
  ]
}
```
