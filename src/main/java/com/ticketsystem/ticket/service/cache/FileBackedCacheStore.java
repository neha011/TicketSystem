package com.ticketsystem.ticket.service.cache;

import com.ticketsystem.ticket.config.PromptCacheProperties;
import com.ticketsystem.ticket.domain.CacheEntry;
import com.ticketsystem.ticket.exception.CacheUnavailableException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * File-backed {@link CacheStore}: a {@link HashMap} of Cache_Key to {@link CacheEntry} guarded by a
 * {@link ReentrantReadWriteLock}, mirrored to the Cache_File through the {@link CacheFileReader} and
 * {@link CacheFileWriter}.
 *
 * <p>Reads take the read lock so lookups and snapshots proceed concurrently; every mutation takes the
 * write lock so writes to the file are serialized and never partially overwrite one another
 * (Requirement 6.1). Write-lock acquisition is bounded to five seconds; a submission that cannot begin
 * its write in that window fails with a {@link CacheUnavailableException} and leaves the persisted
 * entries untouched (Requirement 6.2).
 *
 * <p>At startup the store ensures the Cache_File and its parent directories exist (Requirement 4.3)
 * and loads existing entries into memory (Requirement 4.4). An unreadable file or invalid JSON does
 * not abort startup: the store logs at WARN and begins with an empty cache, still ready to accept
 * submissions (Requirements 7.1, 7.2).
 */
@Component
public class FileBackedCacheStore implements CacheStore {

    private static final Logger log = LoggerFactory.getLogger(FileBackedCacheStore.class);

    /** Maximum time a submission waits to begin its write before failing (Requirement 6.1). */
    private static final long WRITE_LOCK_TIMEOUT_SECONDS = 5L;

    private final CacheFileReader reader;
    private final CacheFileWriter writer;
    private final Path file;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, CacheEntry> entries = new HashMap<>();

    public FileBackedCacheStore(
            CacheFileReader reader, CacheFileWriter writer, PromptCacheProperties properties) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.file = Objects.requireNonNull(properties, "properties").getFile();
    }

    /**
     * Ensures the Cache_File exists and loads it into memory, degrading to an empty cache on any load
     * failure rather than aborting startup (Requirements 4.3, 4.4, 7.1, 7.2).
     */
    @PostConstruct
    public void init() {
        lock.writeLock().lock();
        try {
            ensureFileExists();
            List<CacheEntry> loaded = reader.read();
            entries.clear();
            for (CacheEntry entry : loaded) {
                entries.put(entry.cacheKey(), entry);
            }
            log.info("Loaded {} prompt cache entries from {}", entries.size(), file);
        } catch (CacheFileReadException e) {
            // Unreadable file or invalid JSON: start empty and keep serving (Requirements 7.1, 7.2).
            entries.clear();
            log.warn("Could not load prompt cache file {}; starting with an empty cache", file, e);
        } catch (IOException e) {
            // Could not create the file/dirs: start empty rather than failing to boot (Requirement 7.2).
            entries.clear();
            log.warn("Could not create prompt cache file {}; starting with an empty cache", file, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<CacheEntry> get(String cacheKey) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(entries.get(cacheKey));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Collection<CacheEntry> snapshot() {
        lock.readLock().lock();
        try {
            return List.copyOf(entries.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void mutateAndPersist(Consumer<Map<String, CacheEntry>> mutation) {
        Objects.requireNonNull(mutation, "mutation");

        boolean acquired;
        try {
            acquired = lock.writeLock().tryLock(WRITE_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CacheUnavailableException(e);
        }
        if (!acquired) {
            // Another write held the lock past the 5s budget; leave state untouched (Requirement 6.2).
            log.error("Timed out after {}s waiting to write the prompt cache", WRITE_LOCK_TIMEOUT_SECONDS);
            throw new CacheUnavailableException();
        }

        try {
            // Snapshot before mutating so the in-memory state can be restored if the durable write
            // fails, keeping memory and file consistent (Requirement 7.3).
            Map<String, CacheEntry> rollback = new HashMap<>(entries);

            mutation.accept(entries);

            try {
                writer.write(entries.values());
            } catch (CacheFileWriteException e) {
                // Roll the in-memory mutation back; the writer already left no partial file behind.
                entries.clear();
                entries.putAll(rollback);
                log.error("Failed to persist the prompt cache; rolled back the in-memory mutation", e);
                throw new CacheUnavailableException(e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Creates the Cache_File and any missing parent directories on first startup (Requirement 4.3). */
    private void ensureFileExists() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
    }
}
