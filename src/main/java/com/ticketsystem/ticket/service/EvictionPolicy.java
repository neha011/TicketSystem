package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.domain.CacheEntry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Decides which {@link CacheEntry} instances to evict for TTL expiry and max-size overflow
 * (Requirements 5.4, 5.5).
 *
 * <p>Pure by design: no Spring annotations, no state, and no I/O. Both operations are deterministic
 * functions over the supplied entry set, so eviction ordering and the size bound can be
 * property-tested in isolation without a Spring context, a clock bean, or the store. Applying either
 * operation twice with no intervening change yields the same result (Requirement 5.7, idempotence),
 * because the inputs and the total order used for selection are fully determined by the entries
 * themselves.
 */
public final class EvictionPolicy {

    /**
     * Orders overflow-eviction candidates: least-recently-accessed first, breaking ties by the
     * earlier creation timestamp so the order is total and deterministic even when last-accessed
     * timestamps coincide (Requirement 5.5).
     */
    private static final Comparator<CacheEntry> EVICTION_ORDER =
            Comparator.comparing(CacheEntry::lastAccessedAt)
                    .thenComparing(CacheEntry::createdAt);

    /**
     * Returns the entries whose age relative to {@code now} has reached the configured TTL, i.e.
     * whose creation timestamp is at least {@code ttl} in the past (age {@code >= ttl}, boundary
     * inclusive) (Requirement 5.4).
     *
     * <p>Age is measured as {@code now - createdAt}. The comparison is expressed as
     * {@code createdAt <= now - ttl} to avoid constructing a {@link Duration} per entry.
     *
     * @param entries the current entry set; must not be null (individual elements must be non-null)
     * @param now the reference instant against which age is measured; must not be null
     * @param ttl the configured time-to-live; must not be null
     * @return the expired entries, in encounter order of {@code entries}; never null
     */
    public List<CacheEntry> expired(Collection<CacheEntry> entries, Instant now, Duration ttl) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(ttl, "ttl");

        Instant threshold = now.minus(ttl);
        List<CacheEntry> result = new ArrayList<>();
        for (CacheEntry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            // age >= ttl  <=>  createdAt <= now - ttl (boundary inclusive).
            if (!entry.createdAt().isAfter(threshold)) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * Selects the entries to remove so that the number remaining is at most {@code maxSize}
     * (Requirement 5.5).
     *
     * <p>Candidates are chosen in ascending last-accessed order, ties broken by ascending creation
     * timestamp, so the least-recently-used and then oldest-created entries are removed first. Only
     * as many entries as needed to satisfy the bound are returned; if the set already fits, the
     * result is empty.
     *
     * @param entries the current entry set; must not be null (individual elements must be non-null)
     * @param maxSize the maximum number of entries permitted to remain; expected to be {@code >= 1}
     *     per configuration, but any value is handled (a value {@code <= 0} evicts everything)
     * @return the entries to remove, ordered least-recently-accessed then oldest-created first; never
     *     null
     */
    public List<CacheEntry> overflow(Collection<CacheEntry> entries, int maxSize) {
        Objects.requireNonNull(entries, "entries");

        int excess = entries.size() - maxSize;
        if (excess <= 0) {
            return new ArrayList<>();
        }

        List<CacheEntry> ordered = new ArrayList<>(entries);
        ordered.forEach(entry -> Objects.requireNonNull(entry, "entry"));
        ordered.sort(EVICTION_ORDER);
        return new ArrayList<>(ordered.subList(0, excess));
    }
}
