package com.ticketsystem.ticket.service.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketsystem.ticket.config.JacksonConfig;
import com.ticketsystem.ticket.config.PromptCacheProperties;
import com.ticketsystem.ticket.domain.CacheEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Edge-case coverage for {@link CacheFileReader} and {@link CacheFileWriter} (Requirements 4.5, 7.3):
 * empty/newly-created files yield an empty set, invalid JSON raises, a round-trip preserves entries,
 * and a failed write leaves no partial or temp file behind.
 */
class CacheFileReaderWriterTest {

    private static final ApplicationContextRunner CONTEXT_RUNNER = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class);

    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void resolveConfiguredMapper() {
        // Use the mapper the application actually builds (JSR-310 aware, ISO-8601 instants).
        CONTEXT_RUNNER.run(context -> objectMapper = context.getBean(ObjectMapper.class));
    }

    private PromptCacheProperties propertiesFor(Path file) {
        PromptCacheProperties properties = new PromptCacheProperties();
        properties.setFile(file);
        return properties;
    }

    private CacheEntry sampleEntry(String key) {
        return new CacheEntry(
                key,
                "Summarize this ticket",
                "summarize this ticket|model=gpt-4",
                "The ticket describes ...",
                Instant.parse("2026-09-05T10:15:30Z"),
                Instant.parse("2026-09-05T10:20:00Z"),
                3L);
    }

    @Test
    void shouldReturnEmptySetWhenFileDoesNotExist() {
        Path file = tempDir.resolve("missing.json");
        CacheFileReader reader = new CacheFileReader(objectMapper, propertiesFor(file));

        List<CacheEntry> entries = reader.read();

        assertThat(entries).isEmpty();
    }

    @Test
    void shouldReturnEmptySetWhenFileIsEmpty() throws IOException {
        Path file = tempDir.resolve("empty.json");
        Files.write(file, new byte[0]);
        CacheFileReader reader = new CacheFileReader(objectMapper, propertiesFor(file));

        List<CacheEntry> entries = reader.read();

        assertThat(entries).isEmpty();
    }

    @Test
    void shouldReturnEmptySetWhenFileIsWhitespaceOnly() throws IOException {
        Path file = tempDir.resolve("blank.json");
        Files.writeString(file, "   \n\t  ");
        CacheFileReader reader = new CacheFileReader(objectMapper, propertiesFor(file));

        List<CacheEntry> entries = reader.read();

        assertThat(entries).isEmpty();
    }

    @Test
    void shouldRaiseWhenJsonIsInvalid() throws IOException {
        Path file = tempDir.resolve("corrupt.json");
        Files.writeString(file, "{ this is not json ]");
        CacheFileReader reader = new CacheFileReader(objectMapper, propertiesFor(file));

        assertThatThrownBy(reader::read).isInstanceOf(CacheFileReadException.class);
    }

    @Test
    void shouldRoundTripEntriesThroughWriteThenRead() {
        Path file = tempDir.resolve("cache.json");
        PromptCacheProperties properties = propertiesFor(file);
        CacheFileWriter writer = new CacheFileWriter(objectMapper, properties);
        CacheFileReader reader = new CacheFileReader(objectMapper, properties);

        List<CacheEntry> original = List.of(sampleEntry("key-a"), sampleEntry("key-b"));
        writer.write(original);
        List<CacheEntry> readBack = reader.read();

        assertThat(readBack).containsExactlyInAnyOrderElementsOf(original);
    }

    @Test
    void shouldCreateParentDirectoriesOnFirstWrite() {
        Path file = tempDir.resolve("nested/dir/cache.json");
        CacheFileWriter writer = new CacheFileWriter(objectMapper, propertiesFor(file));

        writer.write(List.of(sampleEntry("key-a")));

        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void shouldWriteEmptyJsonArrayForEmptyCollection() throws IOException {
        Path file = tempDir.resolve("cache.json");
        CacheFileWriter writer = new CacheFileWriter(objectMapper, propertiesFor(file));

        writer.write(List.of());

        assertThat(Files.readString(file).trim()).isEqualTo("[]");
    }

    @Test
    void shouldLeaveNoPartialOrTempFileWhenWriteFails() {
        // A directory where the Cache_File path is itself an existing directory forces the write to
        // fail (cannot write bytes to a directory / cannot move onto it), exercising the cleanup path.
        Path file = tempDir.resolve("cache.json");
        Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");

        // Make the target path a directory so the atomic move onto it fails.
        assertThat(file.toFile().mkdirs()).isTrue();

        CacheFileWriter writer = new CacheFileWriter(objectMapper, propertiesFor(file));

        assertThatThrownBy(() -> writer.write(List.of(sampleEntry("key-a"))))
                .isInstanceOf(CacheFileWriteException.class);

        // No stray temp file left behind (Requirement 7.3).
        assertThat(Files.exists(tempFile)).isFalse();
    }
}
