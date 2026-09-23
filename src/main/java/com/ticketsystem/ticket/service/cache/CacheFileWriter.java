package com.ticketsystem.ticket.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketsystem.ticket.config.PromptCacheProperties;
import com.ticketsystem.ticket.domain.CacheEntry;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Serializes {@link CacheEntry} records to the Cache_File as JSON, atomically (Requirements 4.1, 6.5,
 * 7.3).
 *
 * <p>The write goes to a sibling {@code <file>.tmp} first and is then promoted with an atomic move,
 * so a concurrent reader ever sees either the prior complete file or the new complete file, never a
 * partially written one (Requirement 6.5). On any failure the temporary file is removed so no partial
 * or stray file is left behind, and the failure is raised as a {@link CacheFileWriteException} for the
 * store to convert into a rolled-back mutation and a client-safe 500 (Requirement 7.3).
 *
 * <p>Uses the shared, Boot-managed {@code ObjectMapper} (JSR-310 aware) via constructor injection so
 * {@code Instant} fields are written as ISO-8601, matching what the reader expects.
 */
@Component
public class CacheFileWriter {

    private static final Logger log = LoggerFactory.getLogger(CacheFileWriter.class);

    private static final String TEMP_SUFFIX = ".tmp";

    private final ObjectMapper objectMapper;
    private final Path file;

    public CacheFileWriter(ObjectMapper objectMapper, PromptCacheProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.file = Objects.requireNonNull(properties, "properties").getFile();
    }

    /**
     * Serializes the given entries to the Cache_File as a JSON array, via a temp file and an atomic
     * move.
     *
     * @param entries the full set of entries to persist (the file is a complete snapshot, not a
     *     delta)
     * @throws CacheFileWriteException when serialization or the file I/O fails; the temp file, if any,
     *     is removed before this is thrown so no partial file remains (Requirement 7.3)
     */
    public void write(Collection<CacheEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        // Copy so serialization sees a stable snapshot even if the caller's collection is mutated
        // concurrently; the store holds the write lock, but this keeps the writer self-contained.
        List<CacheEntry> snapshot = new ArrayList<>(entries);

        Path tempFile = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
        try {
            // Parent directories may not exist on the very first write; create them so the temp
            // file can be written (Requirement 4.3 is owned by the store, but this is defensive).
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            byte[] json = objectMapper.writeValueAsBytes(snapshot);
            Files.write(tempFile, json);
            moveIntoPlace(tempFile);
        } catch (IOException | RuntimeException e) {
            cleanUp(tempFile);
            throw new CacheFileWriteException("Failed to write prompt cache file: " + file, e);
        }
    }

    /**
     * Promotes the temp file to the real file with an atomic move, replacing any existing file. Falls
     * back to a plain replacing move on filesystems that do not support atomic moves (still leaving no
     * partial file, since the temp file was already fully written).
     */
    private void moveIntoPlace(Path tempFile) throws IOException {
        try {
            Files.move(
                    tempFile,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            log.warn("Atomic move not supported for {}; falling back to a replacing move", file);
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Best-effort removal of the temp file so a failed write leaves nothing behind (Requirement 7.3). */
    private void cleanUp(Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException cleanupFailure) {
            // The write already failed; a failure to remove the temp file is logged, not rethrown,
            // so it cannot mask the original cause.
            log.warn("Failed to remove temporary prompt cache file {}", tempFile, cleanupFailure);
        }
    }
}
