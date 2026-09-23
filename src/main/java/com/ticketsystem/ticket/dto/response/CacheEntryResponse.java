package com.ticketsystem.ticket.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;

/**
 * Body returned by {@code GET /api/v1/prompt-cache/{cacheKey}} for a matching non-expired entry
 * (Requirement 8.3).
 *
 * <p>Never exposes the internal {@code canonicalIdentity} or the stored {@code cachedResponse} of the
 * domain {@code CacheEntry} — only the inspectable metadata a client needs. Timestamps are ISO-8601
 * UTC {@link Instant}s and all field names are camelCase, consistent with the API standards.
 */
@Schema(description = "A cached prompt entry as returned when inspecting the cache by key.")
public record CacheEntryResponse(

        @Schema(description = "The fixed-length key derived from the normalized prompt and its "
                + "response-affecting parameters.",
                example = "a1b2c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff00")
        String cacheKey,

        @Schema(description = "The original prompt text as submitted.",
                example = "Summarize this ticket")
        String promptText,

        @Schema(description = "When the entry was created, ISO-8601 UTC.",
                example = "2026-09-05T10:15:30Z")
        Instant createdAt,

        @Schema(description = "When the entry was last accessed, ISO-8601 UTC.",
                example = "2026-09-05T10:20:00Z")
        Instant lastAccessedAt,

        @Schema(description = "Number of times the entry has been matched by a submission.",
                example = "3")
        long hitCount) {

    public CacheEntryResponse {
        Objects.requireNonNull(cacheKey, "cacheKey");
        Objects.requireNonNull(promptText, "promptText");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastAccessedAt, "lastAccessedAt");
    }
}
