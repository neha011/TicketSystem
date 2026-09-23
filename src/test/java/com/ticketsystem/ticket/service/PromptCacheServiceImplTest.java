package com.ticketsystem.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Service-orchestration behavior for {@link PromptCacheServiceImpl}: hit/miss handling, defensive
 * validation, collision detection, and TTL expiry (Requirements 1.2, 1.4, 1.5, 1.6, 2.6, 3.2–3.9,
 * 5.4).
 *
 * <p>Uses a real in-memory {@link CacheStore} fake that actually runs the mutation consumer, plus the
 * real pure helpers, so the full orchestration path is exercised. A mutable {@link Clock} makes TTL
 * and last-accessed assertions deterministic.
 */
class PromptCacheServiceImplTest {

    private static final Instant T0 = Instant.parse("2026-09-05T10:15:30Z");

    private InMemoryCacheStore store;
    private MutableClock clock;
    private PromptCacheServiceImpl service;

    @BeforeEach
    void setUp() {
        store = new InMemoryCacheStore();
        clock = new MutableClock(T0);
        PromptCacheProperties properties = new PromptCacheProperties();
        properties.setMaxEntries(3);
        properties.setTtl(Duration.ofSeconds(1000));
        service = new PromptCacheServiceImpl(
                new PromptNormalizer(),
                new CacheKeyDeriver(),
                new EvictionPolicy(),
                store,
                properties,
                clock);
    }

    @Test
    void shouldReportMissAndCreateSingleEntryWhenKeyAbsent() {
        PromptSubmissionResult result =
                service.submit(new SubmitPromptRequest("Summarize this ticket", "gpt-4", null));

        assertThat(result.cacheHit()).isFalse();
        assertThat(result.cachedResponse()).isEmpty();

        Collection<CacheEntry> all = store.snapshot();
        assertThat(all).hasSize(1);
        CacheEntry stored = all.iterator().next();
        assertThat(stored.cacheKey()).isEqualTo(result.cacheKey());
        assertThat(stored.hitCount()).isZero();
        assertThat(stored.createdAt()).isEqualTo(T0);
        assertThat(stored.lastAccessedAt()).isEqualTo(T0);
    }

    @Test
    void shouldTreatWhitespaceVariantAsHitAndUpdateAccessMetadata() {
        service.submit(new SubmitPromptRequest("Summarize this ticket", "gpt-4", null));

        clock.advance(Duration.ofSeconds(30));
        PromptSubmissionResult hit =
                service.submit(new SubmitPromptRequest("  Summarize   this ticket  ", "gpt-4", null));

        assertThat(hit.cacheHit()).isTrue();

        Collection<CacheEntry> all = store.snapshot();
        assertThat(all).hasSize(1);
        CacheEntry stored = all.iterator().next();
        assertThat(stored.hitCount()).isEqualTo(1L);
        assertThat(stored.lastAccessedAt()).isEqualTo(T0.plusSeconds(30));
        assertThat(stored.createdAt()).isEqualTo(T0);
    }

    @Test
    void shouldRejectPromptEmptyAfterNormalizationWithoutPersisting() {
        assertThatThrownBy(() -> service.submit(new SubmitPromptRequest("     ", null, null)))
                .isInstanceOf(ValidationException.class);

        assertThat(store.snapshot()).isEmpty();
    }

    @Test
    void shouldAcceptPromptOfExactlyMaxLength() {
        String prompt = "a".repeat(100_000);

        PromptSubmissionResult result = service.submit(new SubmitPromptRequest(prompt, null, null));

        assertThat(result.cacheHit()).isFalse();
        assertThat(store.snapshot()).hasSize(1);
    }

    @Test
    void shouldRejectPromptExceedingMaxLengthWithoutPersisting() {
        String prompt = "a".repeat(100_001);

        assertThatThrownBy(() -> service.submit(new SubmitPromptRequest(prompt, null, null)))
                .isInstanceOf(ValidationException.class);

        assertThat(store.snapshot()).isEmpty();
    }

    @Test
    void shouldRejectResponseSuppliedForNonMatchingKeyWithoutPersisting() {
        assertThatThrownBy(
                        () ->
                                service.submit(
                                        new SubmitPromptRequest("new prompt", null, "a response")))
                .isInstanceOf(ValidationException.class);

        assertThat(store.snapshot()).isEmpty();
    }

    @Test
    void shouldReturnStoredResponseOnHitWhenPresent() {
        // Seed an entry that already has a stored response.
        String prompt = "cached prompt";
        PromptSubmissionResult miss = service.submit(new SubmitPromptRequest(prompt, null, null));
        store.mutateAndPersist(
                entries ->
                        entries.computeIfPresent(
                                miss.cacheKey(), (k, e) -> e.withResponse("stored answer")));

        PromptSubmissionResult hit = service.submit(new SubmitPromptRequest(prompt, null, null));

        assertThat(hit.cacheHit()).isTrue();
        assertThat(hit.cachedResponse()).contains("stored answer");
    }

    @Test
    void shouldDetectCollisionAndLeaveExistingEntryUnchanged() {
        // Seed an entry stored under the key a real submit for "prompt" derives, but whose canonical
        // identity differs from what that prompt produces — the shape a prior hash collision would
        // leave behind. Submitting "prompt" then finds a key match with a differing canonical identity.
        CacheKeyDeriver deriver = new CacheKeyDeriver();
        String key =
                deriver.deriveKey(deriver.canonicalIdentity("prompt", ResponseParams.ofModel(null)));
        CacheEntry planted =
                CacheEntry.newEntry(key, "other prompt", "a-different-canonical-identity", null, T0);
        store.mutateAndPersist(entries -> entries.put(key, planted));

        assertThatThrownBy(() -> service.submit(new SubmitPromptRequest("prompt", null, null)))
                .isInstanceOf(CacheUnavailableException.class);

        // The existing entry is left exactly as it was (Requirement 2.6).
        assertThat(store.get(key)).contains(planted);
        assertThat(store.snapshot()).hasSize(1);
    }

    @Test
    void shouldTreatExpiredEntryAsMissAndRecreateOnSubmit() {
        PromptSubmissionResult first =
                service.submit(new SubmitPromptRequest("prompt", null, null));
        String key = first.cacheKey();

        // Advance beyond the TTL so the entry's age reaches it.
        clock.advance(Duration.ofSeconds(1000));

        PromptSubmissionResult second = service.submit(new SubmitPromptRequest("prompt", null, null));

        assertThat(second.cacheHit()).isFalse();
        CacheEntry stored = store.get(key).orElseThrow();
        assertThat(stored.hitCount()).isZero();
        assertThat(stored.createdAt()).isEqualTo(T0.plusSeconds(1000));
        assertThat(store.snapshot()).hasSize(1);
    }

    @Test
    void shouldReturnViewForNonExpiredEntryOnFindByKey() {
        PromptSubmissionResult miss = service.submit(new SubmitPromptRequest("prompt", null, null));

        Optional<CacheEntryView> view = service.findByKey(miss.cacheKey());

        assertThat(view).isPresent();
        assertThat(view.get().cacheKey()).isEqualTo(miss.cacheKey());
        assertThat(view.get().promptText()).isEqualTo("prompt");
        assertThat(view.get().hitCount()).isZero();
    }

    @Test
    void shouldReturnEmptyAndRemoveExpiredEntryOnFindByKey() {
        PromptSubmissionResult miss = service.submit(new SubmitPromptRequest("prompt", null, null));
        String key = miss.cacheKey();

        clock.advance(Duration.ofSeconds(1000));

        Optional<CacheEntryView> view = service.findByKey(key);

        assertThat(view).isEmpty();
        assertThat(store.get(key)).isEmpty();
    }

    @Test
    void shouldReturnEmptyOnFindByKeyForUnknownKey() {
        assertThat(service.findByKey("does-not-exist")).isEmpty();
    }

    /** In-memory {@link CacheStore} fake that runs the mutation consumer against a backing map. */
    private static final class InMemoryCacheStore implements CacheStore {
        private final Map<String, CacheEntry> entries = new HashMap<>();

        @Override
        public Optional<CacheEntry> get(String cacheKey) {
            return Optional.ofNullable(entries.get(cacheKey));
        }

        @Override
        public Collection<CacheEntry> snapshot() {
            return List.copyOf(entries.values());
        }

        @Override
        public void mutateAndPersist(Consumer<Map<String, CacheEntry>> mutation) {
            mutation.accept(entries);
        }
    }

    /** A {@link Clock} whose instant can be advanced within a test. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            this.now = this.now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
