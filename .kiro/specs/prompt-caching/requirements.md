# Requirements Document

## Introduction

The Prompt Caching feature persists prompts that a user submits to an AI agent into a durable file-backed cache. Each prompt is normalized, hashed to a stable key, and stored so that identical future prompts can be looked up and reused without re-invoking the AI agent. The cache is exposed through the existing Spring Boot backend, follows the project's REST/JSON API standards, and stores entries in a JSON-based cache file on the server filesystem. The feature covers write-on-submit behavior, lookup/reuse of previously cached prompts, size- and age-based eviction, safe concurrent access, and consistent error handling.

This document defines *what* the feature must do. Specific class names, storage schemas, and algorithms are deferred to the design document.

## Glossary

- **Prompt**: The text content a user submits to be processed by an AI agent. For caching purposes, a Prompt is the normalized text plus any parameters that affect the AI agent response (for example model identifier).
- **Prompt_Cache**: The subsystem responsible for storing, retrieving, and evicting cached prompts and their associated responses.
- **Cache_Entry**: A single stored record consisting of a cache key, the original prompt text, an optional cached response, a creation timestamp, a last-accessed timestamp, and a hit count.
- **Cache_Key**: A deterministic, fixed-length identifier derived from the normalized prompt text and response-affecting parameters, used to detect identical prompts.
- **Cache_File**: The file on the server filesystem where Cache_Entries are persisted in JSON format.
- **Cache_Writer**: The component that serializes Cache_Entries and persists them to the Cache_File.
- **Cache_Reader**: The component that deserializes Cache_Entries from the Cache_File into the in-memory representation.
- **Cache_Hit**: A lookup where the Cache_Key derived from the incoming prompt matches an existing, non-expired Cache_Entry.
- **Cache_Miss**: A lookup where no matching, non-expired Cache_Entry exists for the derived Cache_Key.
- **Eviction**: The removal of Cache_Entries to enforce configured maximum size or maximum age limits.
- **TTL**: Time-to-live; the configured maximum age after which a Cache_Entry is considered expired.
- **Normalization**: The deterministic transformation of raw prompt text into a canonical form (for example trimming leading and trailing whitespace and collapsing internal whitespace runs into a single space) prior to Cache_Key derivation.

## Requirements

### Requirement 1: Cache a submitted prompt

**User Story:** As a user of the AI agent, I want every prompt I submit to be persisted to a cache file, so that my prompts are retained across application restarts and can be reused later.

#### Acceptance Criteria

1. WHEN a user submits a prompt, THE Prompt_Cache SHALL derive a Cache_Key from the normalized prompt text and response-affecting parameters.
2. WHEN a user submits a prompt whose derived Cache_Key does not match any existing Cache_Entry, THE Prompt_Cache SHALL create a new Cache_Entry containing the Cache_Key, the original prompt text, the creation timestamp, the last-accessed timestamp, and a hit count of zero.
3. WHEN a new Cache_Entry is created, THE Cache_Writer SHALL persist the Cache_Entry to the Cache_File, and THE Prompt_Cache SHALL return the success response only after the Cache_Entry has been written to the Cache_File.
4. WHEN a user submits a prompt whose derived Cache_Key matches an existing Cache_Entry, THE Prompt_Cache SHALL update the last-accessed timestamp of the matching Cache_Entry to the submission time and increment its hit count by exactly one, and SHALL NOT create an additional Cache_Entry for that Cache_Key.
5. IF the submitted prompt text is empty after Normalization, THEN THE Prompt_Cache SHALL reject the submission with a validation error indicating the prompt is empty, and SHALL NOT create or persist any Cache_Entry.
6. IF the submitted prompt text exceeds 100000 characters before Normalization, THEN THE Prompt_Cache SHALL reject the submission with a validation error indicating the maximum length was exceeded, and SHALL NOT create or persist any Cache_Entry, WHERE a prompt of exactly 100000 characters before Normalization is accepted.

### Requirement 2: Normalize prompts and derive stable keys

**User Story:** As a user, I want prompts that differ only in incidental whitespace to be treated as the same prompt, so that trivial formatting differences do not create redundant cache entries.

#### Acceptance Criteria

1. WHEN a user submits a prompt, THE Prompt_Cache SHALL apply Normalization to the raw prompt text before deriving the Cache_Key.
2. WHEN Normalization is applied to raw prompt text, THE Prompt_Cache SHALL produce the same normalized text on every application to the same raw prompt text, so that key derivation is deterministic.
3. WHEN two submitted prompts produce identical normalized text and identical response-affecting parameters, THE Prompt_Cache SHALL derive identical Cache_Keys for both prompts.
4. WHEN two submitted prompts differ in normalized text, or differ in at least one response-affecting parameter, THE Prompt_Cache SHALL derive different Cache_Keys for the two prompts.
5. THE Prompt_Cache SHALL derive every Cache_Key as a fixed-length identifier whose length is identical for all prompts regardless of the length of the input prompt text.
6. IF two prompts that produce different normalized text or different response-affecting parameters would derive the same Cache_Key, THEN THE Prompt_Cache SHALL treat the derivation as a failure and reject the affected submission with an error response that indicates key derivation failed without exposing internal exception details, while retaining the existing matching Cache_Entry unchanged.

### Requirement 3: Look up and reuse cached prompts

**User Story:** As a user, I want a previously cached prompt to be recognized on resubmission, so that the system can reuse the stored response instead of recomputing it.

#### Acceptance Criteria

1. WHEN a user submits a prompt, THE Prompt_Cache SHALL look up a Cache_Entry whose Cache_Key equals the Cache_Key derived from the normalized prompt text and response-affecting parameters of the submitted prompt.
2. WHEN a lookup results in a Cache_Hit and the matching Cache_Entry has a stored cached response, THE Prompt_Cache SHALL return the matching Cache_Entry together with its stored cached response to the caller.
3. WHEN a lookup results in a Cache_Hit and the matching Cache_Entry has no stored cached response, THE Prompt_Cache SHALL return the matching Cache_Entry to the caller with an indication that no cached response is present.
4. WHEN a lookup results in a Cache_Miss, THE Prompt_Cache SHALL return a result to the caller that explicitly indicates a Cache_Miss and contains no Cache_Entry.
5. WHEN a Cache_Hit occurs, THE Prompt_Cache SHALL set the last-accessed timestamp of the matching Cache_Entry to the current time.
6. WHEN a Cache_Hit occurs, THE Prompt_Cache SHALL increment the hit count of the matching Cache_Entry by one.
7. WHEN a Cache_Hit updates the last-accessed timestamp or hit count of a Cache_Entry, THE Cache_Writer SHALL persist the updated Cache_Entry to the Cache_File.
8. WHEN a cached response is produced for a submitted prompt, THE Prompt_Cache SHALL store the cached response in the Cache_Entry whose Cache_Key matches the submitted prompt and THE Cache_Writer SHALL persist the updated Cache_Entry to the Cache_File.
9. IF a cached response is produced for a submitted prompt whose Cache_Key does not match any existing Cache_Entry, THEN THE Prompt_Cache SHALL report a validation error and SHALL NOT persist the response.

### Requirement 4: Cache file format and location

**User Story:** As an operator, I want the cache stored in a well-defined file at a configurable location, so that I can manage, back up, and inspect the cache.

#### Acceptance Criteria

1. THE Cache_Writer SHALL persist Cache_Entries to the Cache_File in JSON format.
2. THE Prompt_Cache SHALL determine the Cache_File path from externalized configuration, and WHERE the configuration does not specify a Cache_File path, THE Prompt_Cache SHALL use a documented default path.
3. WHEN the application starts and the configured Cache_File path does not exist, THE Prompt_Cache SHALL create the Cache_File and any missing parent directories.
4. WHEN the application starts, THE Cache_Reader SHALL load existing Cache_Entries from the Cache_File into the in-memory representation.
5. WHEN the application starts and the Cache_File is empty or newly created, THE Cache_Reader SHALL initialize the in-memory representation as an empty set of Cache_Entries.
6. FOR ALL Cache_Entries, writing the Cache_Entries to the Cache_File and then reading them back SHALL produce a set of Cache_Entries equal to the original, where two Cache_Entries are equal when their Cache_Key, original prompt text, cached response, creation timestamp, last-accessed timestamp, and hit count are equal (round-trip property).

### Requirement 5: Eviction by size and age

**User Story:** As an operator, I want the cache to enforce size and age limits, so that the Cache_File does not grow without bound.

#### Acceptance Criteria

1. THE Prompt_Cache SHALL read a configured maximum number of Cache_Entries from externalized configuration, where the configured value is an integer in the range 1 to 1,000,000 inclusive, and SHALL apply a default of 10,000 when the value is not present in configuration.
2. THE Prompt_Cache SHALL read a configured TTL from externalized configuration, where the configured value is a duration in the range 1 second to 31,536,000 seconds (365 days) inclusive, and SHALL apply a default of 604,800 seconds (7 days) when the value is not present in configuration.
3. IF the configured maximum number of Cache_Entries or the configured TTL is present but falls outside its permitted range or cannot be interpreted as the expected type, THEN THE Prompt_Cache SHALL reject the configured value, apply the corresponding default from criteria 1 and 2, and log the rejection at WARN level.
4. WHEN a lookup encounters a Cache_Entry whose age, measured as the elapsed time between the Cache_Entry creation timestamp and the current time, is greater than or equal to the configured TTL, THE Prompt_Cache SHALL treat the lookup as a Cache_Miss and remove the expired Cache_Entry.
5. IF creating a new Cache_Entry would cause the number of Cache_Entries to exceed the configured maximum, THEN THE Prompt_Cache SHALL evict Cache_Entries in ascending order of last-accessed timestamp, and where two Cache_Entries share the same last-accessed timestamp SHALL evict the one with the earlier creation timestamp first, until the number of remaining Cache_Entries is less than or equal to the configured maximum, before persisting the new Cache_Entry.
6. WHEN Eviction removes one or more Cache_Entries, THE Cache_Writer SHALL persist the updated set of Cache_Entries to the Cache_File.
7. WHEN Eviction is applied twice with no intervening changes, THE Prompt_Cache SHALL produce the same set of remaining Cache_Entries on the second application as on the first (idempotence).

### Requirement 6: Concurrent access

**User Story:** As a user, I want the cache to behave correctly when multiple prompts are submitted at the same time, so that concurrent submissions do not corrupt the Cache_File or lose entries.

#### Acceptance Criteria

1. WHILE multiple prompt submissions are processed concurrently, THE Prompt_Cache SHALL serialize writes to the Cache_File so that no write partially overwrites another write, and SHALL allow each submission to wait up to 5 seconds to begin its write.
2. IF a submission cannot begin its write to the Cache_File within 5 seconds because other writes are in progress, THEN THE Prompt_Cache SHALL return an error response that indicates the caching operation failed without exposing internal exception details, and SHALL leave the previously persisted Cache_Entries unchanged.
3. WHEN two or more concurrent submissions derive the same Cache_Key, THE Prompt_Cache SHALL retain exactly one Cache_Entry for that Cache_Key and SHALL increase that Cache_Entry's hit count by exactly the number of concurrent submissions that matched it.
4. WHEN two or more concurrent submissions derive distinct Cache_Keys, THE Prompt_Cache SHALL retain one Cache_Entry for each distinct Cache_Key with no submission's Cache_Entry lost, subject to the eviction limits defined in Requirement 5.
5. WHILE a write to the Cache_File is in progress, THE Cache_Reader SHALL either read the prior complete contents or the new complete contents of the Cache_File, and SHALL NOT read a partially written Cache_File.

### Requirement 7: Error handling

**User Story:** As a user, I want cache failures to be reported clearly and to not break prompt submission, so that a caching problem does not prevent me from using the AI agent.

#### Acceptance Criteria

1. IF at startup the Cache_File cannot be read or its contents are not valid JSON, THEN THE Prompt_Cache SHALL start with an empty in-memory cache and log the error at WARN level.
2. WHEN the Cache_File could not be loaded at startup, THE Prompt_Cache SHALL continue to accept prompt submissions and process them against the empty in-memory cache.
3. IF the Cache_Writer fails to persist a Cache_Entry to the Cache_File, THEN THE Prompt_Cache SHALL return a 500 Internal Server Error response that indicates the caching operation failed, without exposing internal exception details, and SHALL NOT leave a partially written Cache_File.
4. IF a prompt submission fails validation, THEN THE Prompt_Cache SHALL return a 400 Bad Request response following the standard error response shape.
5. WHEN an error response is returned, THE Prompt_Cache SHALL include the timestamp as an ISO-8601 value, the numeric status, the error, the message, and the path of the originating request as defined by the API error response shape.

### Requirement 8: Cache API

**User Story:** As a frontend developer, I want REST endpoints to submit prompts to the cache and inspect cache state, so that the client can integrate prompt caching.

#### Acceptance Criteria

1. WHEN a client sends a POST request with a prompt to the cache submission endpoint and the derived Cache_Key matches an existing non-expired Cache_Entry, THE Prompt_Cache SHALL return a 200 OK response whose body contains the Cache_Key, a boolean cacheHit field set to true, and the cached response if one is stored.
2. WHEN a client sends a POST request with a prompt to the cache submission endpoint and the request creates a new Cache_Entry, THE Prompt_Cache SHALL return a 201 Created response that includes a Location header identifying the created Cache_Entry and a body containing the Cache_Key, a boolean cacheHit field set to false, and the cached response if one is stored, using camelCase JSON field names and ISO-8601 timestamps.
3. WHEN a client sends a GET request for a cached prompt by Cache_Key that matches an existing non-expired Cache_Entry, THE Prompt_Cache SHALL return a 200 OK response whose body contains the Cache_Key, the original prompt text, the creation timestamp, the last-accessed timestamp, and the hit count, using camelCase JSON field names and ISO-8601 timestamps.
4. WHEN a client requests a cached prompt by Cache_Key that does not match any existing non-expired Cache_Entry, THE Prompt_Cache SHALL return a 404 Not Found response following the standard error response shape.
5. IF a client sends a request to a cache endpoint whose body is malformed, is missing the prompt field, has a prompt that is empty after Normalization, or has a prompt exceeding 100000 characters before Normalization, THEN THE Prompt_Cache SHALL return a 400 Bad Request response following the standard error response shape without creating a Cache_Entry.
6. WHEN a client sends a request to any cache endpoint under nominal load of up to 50 concurrent requests, THE Prompt_Cache SHALL return a response within 500 milliseconds at the 95th percentile.
7. THE Prompt_Cache SHALL document each cache endpoint using springdoc-openapi annotations such that the generated OpenAPI description includes, for each endpoint, its request body schema, every response status code it can return, and the response body schema for each such status.
