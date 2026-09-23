package com.ticketsystem.ticket.service.cache;

import com.ticketsystem.ticket.domain.CacheEntry;
import com.ticketsystem.ticket.exception.CacheUnavailableException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The persistence boundary for the prompt cache: an in-memory map of {@link CacheEntry} records that
 * is durably mirrored to the Cache_File. It plays the role a repository plays elsewhere in the
 * service, but is file-backed rather than JPA-backed because the requirements mandate a JSON file on
 * the filesystem (Requirements 4.3, 4.4).
 *
 * <p>Reads ({@link #get} / {@link #snapshot}) run against the in-memory map for speed; every mutation
 * goes through {@link #mutateAndPersist}, which serializes writes under a single lock and flushes the
 * full set to the Cache_File before reporting success, so a restart reloads an equivalent state
 * (Requirements 6.1, 6.5).
 */
public interface CacheStore {

    /**
     * Looks up the entry for a key against the in-memory map.
     *
     * @param cacheKey the derived Cache_Key
     * @return the entry if present, otherwise empty
     */
    Optional<CacheEntry> get(String cacheKey);

    /**
     * @return an immutable snapshot of all currently stored entries; iterating it never observes a
     *     concurrent mutation
     */
    Collection<CacheEntry> snapshot();

    /**
     * Mutates the in-memory map and durably persists the result, atomically and under the write lock.
     *
     * <p>The mutation receives a live, mutable view of the key-to-entry map; when it returns, the new
     * state is serialized to the Cache_File via the {@link CacheFileWriter}. Acquisition of the write
     * lock is bounded to 5 seconds (Requirements 6.1, 6.2); on timeout the state is left untouched. If
     * the durable write fails, the in-memory mutation is rolled back from a pre-mutation snapshot so
     * memory and file stay consistent (Requirement 7.3).
     *
     * @param mutation the change to apply to the entry map
     * @throws CacheUnavailableException when the write lock cannot be acquired within 5 seconds or the
     *     durable write fails; in both cases the previously persisted entries are left unchanged
     */
    void mutateAndPersist(Consumer<Map<String, CacheEntry>> mutation);
}
