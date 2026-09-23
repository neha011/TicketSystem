package com.ticketsystem.ticket.service.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketsystem.ticket.config.PromptCacheProperties;
import com.ticketsystem.ticket.domain.CacheEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Deserializes {@link CacheEntry} records from the Cache_File into memory (Requirements 4.1, 4.5).
 *
 * <p>Reads the JSON array the {@link CacheFileWriter} produces. An empty or newly created file yields
 * an empty list (Requirement 4.5). Invalid JSON is surfaced as a {@link CacheFileReadException} so the
 * caller — the store, at startup — can degrade to an empty cache with a WARN log rather than failing
 * to start (Requirement 7.1); this component does not decide that policy itself.
 *
 * <p>Uses the shared, Boot-managed {@code ObjectMapper} (JSR-310 aware) via constructor injection so
 * {@code Instant} fields round-trip as ISO-8601 exactly as they are written.
 */
@Component
public class CacheFileReader {

    private static final TypeReference<List<CacheEntry>> ENTRY_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final Path file;

    public CacheFileReader(ObjectMapper objectMapper, PromptCacheProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.file = Objects.requireNonNull(properties, "properties").getFile();
    }

    /**
     * Reads and deserializes the Cache_File.
     *
     * @return the stored entries; an empty list when the file does not exist, is empty, or contains
     *     only whitespace (Requirement 4.5)
     * @throws CacheFileReadException when the file exists with non-empty content that is not valid
     *     JSON, or cannot be read (Requirement 7.1)
     */
    public List<CacheEntry> read() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            byte[] content = Files.readAllBytes(file);
            // An empty or whitespace-only file is a newly created / never-written Cache_File, not a
            // parse error: initialize to an empty set (Requirement 4.5).
            if (isBlank(content)) {
                return List.of();
            }
            List<CacheEntry> entries = objectMapper.readValue(content, ENTRY_LIST);
            return entries == null ? List.of() : List.copyOf(entries);
        } catch (IOException e) {
            // Invalid JSON or an unreadable file: raise so the store can start empty + WARN
            // (Requirement 7.1). No internal detail is placed anywhere client-facing.
            throw new CacheFileReadException("Failed to read prompt cache file: " + file, e);
        }
    }

    private static boolean isBlank(byte[] content) {
        for (byte b : content) {
            if (!Character.isWhitespace((char) b)) {
                return false;
            }
        }
        return true;
    }
}
