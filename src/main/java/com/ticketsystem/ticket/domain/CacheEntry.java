package com.ticketsystem.ticket.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * A single stored prompt-cache record: the derived key, the original prompt text, the canonical
 * identity used to confirm a key match, an optional cached response, creation and last-accessed
 * timestamps, and a hit count (Requirements 1.2, 4.1).
 *
 * <p>Modeled as an immutable record so an update (a hit-count increment or a last-accessed refresh)
 * produces a new value rather than mutating shared state. It is the in-memory value and the persisted
 * JSON shape at once; the explicit Jackson annotations keep (de)serialization stable regardless of
 * record component order and consistent with the shared, JSR-310-aware {@code ObjectMapper}.
 *
 * <p>Two entries are equal for the round-trip property when {@code cacheKey}, {@code promptText},
 * {@code cachedResponse}, {@code createdAt}, {@code lastAccessedAt}, and {@code hitCount} are equal
 * (Requirement 4.6); the record's generated equality covers exactly these components plus
 * {@code canonicalIdentity}, which is itself derived from the prompt and params.
 */
public record CacheEntry(
        @JsonProperty("cacheKey") String cacheKey,
        @JsonProperty("promptText") String promptText,
        @JsonProperty("canonicalIdentity") String canonicalIdentity,
        @JsonProperty("cachedResponse") String cachedResponse,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("lastAccessedAt") Instant lastAccessedAt,
        @JsonProperty("hitCount") long hitCount) {

    @JsonCreator
    public CacheEntry {
        // hit count is a count, never negative
        if (hitCount < 0) {
            throw new IllegalArgumentException("hitCount must not be negative");
        }
    }

    /**
     * A brand-new entry for a cache miss: hit count zero, creation and last-accessed timestamps
     * equal to the submission time (Requirements 1.2, 3.4).
     */
    public static CacheEntry newEntry(
            String cacheKey,
            String promptText,
            String canonicalIdentity,
            String cachedResponse,
            Instant now) {
        return new CacheEntry(cacheKey, promptText, canonicalIdentity, cachedResponse, now, now, 0L);
    }

    /**
     * Copy recording a cache hit: hit count incremented by exactly one and last-accessed set to the
     * access time, all other fields preserved (Requirements 1.4, 3.5, 3.6).
     */
    public CacheEntry recordHit(Instant accessedAt) {
        return new CacheEntry(
                cacheKey, promptText, canonicalIdentity, cachedResponse, createdAt, accessedAt, hitCount + 1);
    }

    /** Copy storing a cached response for this entry, preserving all other fields (Requirement 3.8). */
    public CacheEntry withResponse(String response) {
        return new CacheEntry(
                cacheKey, promptText, canonicalIdentity, response, createdAt, lastAccessedAt, hitCount);
    }

    /** True when a cached response has been stored for this entry (Requirements 3.2, 3.3). */
    public boolean hasResponse() {
        return cachedResponse != null;
    }
}
