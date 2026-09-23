package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.config.PromptCacheProperties;
import com.ticketsystem.ticket.domain.CacheEntry;
import com.ticketsystem.ticket.domain.ResponseParams;
import com.ticketsystem.ticket.dto.request.SubmitPromptRequest;
import com.ticketsystem.ticket.exception.CacheUnavailableException;
import com.ticketsystem.ticket.exception.ValidationException;
import com.ticketsystem.ticket.service.cache.CacheStore;
import com.ticketsystem.ticket.service.result.CacheEntryView;
import com.ticketsystem.ticket.service.result.PromptSubmissionResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default {@link PromptCacheService}: wires the pure {@link PromptNormalizer}, {@link CacheKeyDeriver}
 * and {@link EvictionPolicy} to the file-backed {@link CacheStore}, using the injectable
 * {@link Clock} for every timestamp so TTL and last-accessed behavior is deterministic and testable.
 *
 * <p>Read-then-mutate races are avoided by performing lookup, collision detection, eviction and the
 * hit/miss decision <em>inside</em> the store's {@code mutateAndPersist} critical section, which holds
 * the write lock; a validation or collision failure thrown from within that section aborts before any
 * mutation or file write, leaving the persisted state unchanged (Requirements 2.6, 6.2, 7.3).
 */
@Service
public class PromptCacheServiceImpl implements PromptCacheService {

    private static final Logger log = LoggerFactory.getLogger(PromptCacheServiceImpl.class);

    /**
     * Maximum raw prompt length, inclusive (Requirement 1.6). Also enforced at the DTO boundary via
     * {@code @TrimmedSize}; re-checked here defensively because the service must hold the invariant
     * independently of the web layer.
     */
    private static final int MAX_PROMPT_LENGTH = 100_000;

    private final PromptNormalizer normalizer;
    private final CacheKeyDeriver keyDeriver;
    private final EvictionPolicy evictionPolicy;
    private final CacheStore store;
    private final PromptCacheProperties properties;
    private final Clock clock;

    public PromptCacheServiceImpl(
            PromptNormalizer normalizer,
            CacheKeyDeriver keyDeriver,
            EvictionPolicy evictionPolicy,
            CacheStore store,
            PromptCacheProperties properties,
            Clock clock) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.keyDeriver = Objects.requireNonNull(keyDeriver, "keyDeriver");
        this.evictionPolicy = Objects.requireNonNull(evictionPolicy, "evictionPolicy");
        this.store = Objects.requireNonNull(store, "store");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PromptSubmissionResult submit(SubmitPromptRequest request) {
        Objects.requireNonNull(request, "request");

        String rawPrompt = request.prompt();
        // Length is measured against the raw text before normalization (Requirement 1.6, inclusive).
        if (rawPrompt != null && rawPrompt.length() > MAX_PROMPT_LENGTH) {
            throw new ValidationException(
                    "prompt must not exceed " + MAX_PROMPT_LENGTH + " characters");
        }

        String normalized = normalizer.normalize(rawPrompt);
        if (normalized.isEmpty()) {
            // Defensive: the controller's @TrimmedSize also rejects this, but the service owns the
            // invariant independently (Requirement 1.5). Nothing is persisted.
            throw new ValidationException("prompt must not be empty after normalization");
        }

        ResponseParams params = ResponseParams.ofModel(request.model());
        String canonicalIdentity = keyDeriver.canonicalIdentity(normalized, params);
        String cacheKey = keyDeriver.deriveKey(canonicalIdentity);
        String suppliedResponse = request.response();

        // Capture the outcome from inside the critical section so the reported hit/miss reflects the
        // exact persisted state.
        AtomicReference<PromptSubmissionResult> outcome = new AtomicReference<>();

        store.mutateAndPersist(entries -> {
            Instant now = clock.instant();
            CacheEntry existing = entries.get(cacheKey);

            boolean expired = existing != null && isExpired(existing, now);

            if (existing != null && !expired) {
                // Same key but a different canonical identity means the derivation collided; reject and
                // leave the stored entry untouched (Requirement 2.6). No mutation has happened yet.
                if (!canonicalIdentity.equals(existing.canonicalIdentity())) {
                    log.error(
                            "Cache key collision on {}: differing canonical identities map to the same key",
                            cacheKey);
                    throw new CacheUnavailableException();
                }

                // Cache hit: bump hit count and last-accessed, keep the single entry, return the stored
                // response iff present (Requirements 1.4, 3.2, 3.3, 3.5, 3.6, 3.7).
                CacheEntry updated = existing.recordHit(now);
                entries.put(cacheKey, updated);
                outcome.set(PromptSubmissionResult.hit(updated));
                return;
            }

            // From here it is a miss: either the key was absent or the matching entry has expired.
            // A response supplied for a key that matches no existing entry is a validation error and
            // must not be persisted (Requirement 3.9).
            if (suppliedResponse != null) {
                throw new ValidationException(
                        "a response cannot be supplied for a prompt that is not already cached");
            }

            if (expired) {
                // Treat the expired entry as a miss and remove it before recreating (Requirement 5.4).
                entries.remove(cacheKey);
            }

            // Enforce TTL and size limits before inserting the new entry (Requirements 5.4, 5.5).
            evict(entries, now);

            CacheEntry created =
                    CacheEntry.newEntry(cacheKey, rawPrompt, canonicalIdentity, null, now);
            entries.put(cacheKey, created);
            outcome.set(PromptSubmissionResult.miss(created));
        });

        return outcome.get();
    }

    @Override
    public Optional<CacheEntryView> findByKey(String cacheKey) {
        Objects.requireNonNull(cacheKey, "cacheKey");

        Optional<CacheEntry> found = store.get(cacheKey);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        CacheEntry entry = found.get();
        if (!isExpired(entry, clock.instant())) {
            return Optional.of(CacheEntryView.from(entry));
        }

        // Expired: treat as a miss and remove it so a subsequent read stays consistent and the
        // controller answers 404 (Requirements 3.1, 5.4, 8.4).
        store.mutateAndPersist(entries -> {
            CacheEntry current = entries.get(cacheKey);
            // Re-check under the write lock: only remove if it is still the same expired entry.
            if (current != null && isExpired(current, clock.instant())) {
                entries.remove(cacheKey);
            }
        });
        return Optional.empty();
    }

    /**
     * Removes expired entries then trims overflow so a new entry can be added within the configured
     * maximum, mutating the supplied map in place (Requirements 5.4, 5.5). Called under the write
     * lock.
     */
    private void evict(Map<String, CacheEntry> entries, Instant now) {
        Duration ttl = properties.getTtl();
        List<CacheEntry> expired = evictionPolicy.expired(entries.values(), now, ttl);
        for (CacheEntry entry : expired) {
            entries.remove(entry.cacheKey());
        }

        // Leave room for the one entry about to be inserted: bound the retained set to maxEntries - 1.
        int roomForNewEntry = Math.max(0, properties.getMaxEntries() - 1);
        List<CacheEntry> overflow = evictionPolicy.overflow(entries.values(), roomForNewEntry);
        for (CacheEntry entry : overflow) {
            entries.remove(entry.cacheKey());
        }
    }

    /** An entry is expired when its age ({@code now - createdAt}) has reached the configured TTL. */
    private boolean isExpired(CacheEntry entry, Instant now) {
        Duration ttl = properties.getTtl();
        Instant threshold = now.minus(ttl);
        // age >= ttl  <=>  createdAt <= now - ttl (boundary inclusive), matching EvictionPolicy.
        return !entry.createdAt().isAfter(threshold);
    }
}
