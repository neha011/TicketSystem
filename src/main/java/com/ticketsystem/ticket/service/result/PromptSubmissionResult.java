package com.ticketsystem.ticket.service.result;

import com.ticketsystem.ticket.domain.CacheEntry;
import java.util.Objects;
import java.util.Optional;

/**
 * Service-layer outcome of {@link com.ticketsystem.ticket.service.PromptCacheService#submit}
 * (Requirements 3.2, 3.3, 3.4, 8.1, 8.2).
 *
 * <p>This is deliberately a service-layer type rather than a transport DTO: it carries just enough
 * for the controller to decide the HTTP status ({@code 200} on a hit, {@code 201} on a new entry) and
 * to build both the {@code Location} header and the {@code PromptSubmissionResponse} body, while
 * keeping web concerns (JSON shape, {@code @Schema}) out of the service layer per the layering
 * guideline.
 *
 * <p>{@code cacheHit} distinguishes the two outcomes; {@code cachedResponse} is an {@link Optional}
 * so a hit with no stored response is an explicit "no response present" rather than a bare
 * {@code null} (Requirement 3.3).
 */
public record PromptSubmissionResult(
        String cacheKey,
        boolean cacheHit,
        Optional<String> cachedResponse) {

    public PromptSubmissionResult {
        Objects.requireNonNull(cacheKey, "cacheKey");
        Objects.requireNonNull(cachedResponse, "cachedResponse");
    }

    /**
     * Result for a cache miss that created a new entry: {@code cacheHit=false}, with the stored
     * response iff the submission supplied one (Requirements 1.2, 3.4, 8.2).
     */
    public static PromptSubmissionResult miss(CacheEntry entry) {
        return new PromptSubmissionResult(
                entry.cacheKey(), false, Optional.ofNullable(entry.cachedResponse()));
    }

    /**
     * Result for a cache hit against an existing non-expired entry: {@code cacheHit=true}, returning
     * the stored response iff one is present (Requirements 1.4, 3.2, 3.3, 8.1).
     */
    public static PromptSubmissionResult hit(CacheEntry entry) {
        return new PromptSubmissionResult(
                entry.cacheKey(), true, Optional.ofNullable(entry.cachedResponse()));
    }
}
