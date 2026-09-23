package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.dto.request.SubmitPromptRequest;
import com.ticketsystem.ticket.exception.CacheUnavailableException;
import com.ticketsystem.ticket.exception.ValidationException;
import com.ticketsystem.ticket.service.result.CacheEntryView;
import com.ticketsystem.ticket.service.result.PromptSubmissionResult;
import java.util.Optional;

/**
 * Orchestrates the prompt cache: normalize a submitted prompt, derive its Cache_Key, look it up,
 * record the hit or miss, evict as needed, and durably persist the result (Requirements 1.x, 2.x,
 * 3.x, 5.4, 5.5).
 *
 * <p>All caching policy lives here; the controller is a thin HTTP boundary that maps between DTOs and
 * the service-layer {@link PromptSubmissionResult} / {@link CacheEntryView} types this interface
 * returns.
 */
public interface PromptCacheService {

    /**
     * Submit a prompt: normalize, derive the key, look up the entry, record a hit or miss, evict, and
     * persist before reporting success (Requirements 1.2, 1.4, 3.2–3.9, 5.4, 5.5).
     *
     * @param request the submitted prompt with its optional model and optional response to store
     * @return whether the submission hit or missed, plus the stored response when present
     * @throws ValidationException when the prompt is empty after normalization, exceeds the length
     *     cap, or supplies a response for a key that matches no existing entry (Requirements 1.5, 1.6,
     *     3.9)
     * @throws CacheUnavailableException on a key-derivation collision or a persistence failure
     *     (Requirements 2.6, 7.3)
     */
    PromptSubmissionResult submit(SubmitPromptRequest request);

    /**
     * Read a cache entry by key, treating an entry whose age has reached the TTL as absent and
     * removing it (Requirements 3.1, 5.4). An empty result drives the controller's {@code 404}
     * (Requirement 8.4).
     *
     * @param cacheKey the Cache_Key to inspect
     * @return the entry's inspectable view, or empty when no matching non-expired entry exists
     */
    Optional<CacheEntryView> findByKey(String cacheKey);
}
