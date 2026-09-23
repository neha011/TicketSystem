package com.ticketsystem.ticket.service.result;

import com.ticketsystem.ticket.domain.CacheEntry;
import java.time.Instant;
import java.util.Objects;

/**
 * Service-layer projection of a {@link CacheEntry} for inspection by key
 * (Requirement 8.3), returned by
 * {@link com.ticketsystem.ticket.service.PromptCacheService#findByKey}.
 *
 * <p>Exposes only the inspectable metadata a client needs and deliberately omits the internal
 * {@code canonicalIdentity} and the stored {@code cachedResponse}. Keeping this as a service-layer
 * view rather than the transport {@code CacheEntryResponse} keeps DTO/web concerns out of the service
 * layer; the controller maps this view to the response DTO.
 */
public record CacheEntryView(
        String cacheKey,
        String promptText,
        Instant createdAt,
        Instant lastAccessedAt,
        long hitCount) {

    public CacheEntryView {
        Objects.requireNonNull(cacheKey, "cacheKey");
        Objects.requireNonNull(promptText, "promptText");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastAccessedAt, "lastAccessedAt");
    }

    /** Projects a domain {@link CacheEntry} onto its inspectable metadata. */
    public static CacheEntryView from(CacheEntry entry) {
        return new CacheEntryView(
                entry.cacheKey(),
                entry.promptText(),
                entry.createdAt(),
                entry.lastAccessedAt(),
                entry.hitCount());
    }
}
