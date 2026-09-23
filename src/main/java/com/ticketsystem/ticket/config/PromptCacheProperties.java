package com.ticketsystem.ticket.config;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalized configuration for the prompt cache: the Cache_File path, the maximum number of
 * entries, and the entry TTL (Requirements 4.2, 5.1, 5.2).
 *
 * <p>Grouped as typed properties rather than scattered {@code @Value} injections, per the Spring Boot
 * steering doc. A {@code @PostConstruct} normalization step clamps out-of-range or unset values to
 * the documented defaults and logs the rejection at WARN, so a misconfigured environment still starts
 * with safe values rather than aborting startup (Requirement 5.3).
 */
@Component
@ConfigurationProperties(prefix = "prompt-cache")
public class PromptCacheProperties {

    private static final Logger log = LoggerFactory.getLogger(PromptCacheProperties.class);

    /** Documented defaults (Requirements 4.2, 5.1, 5.2). */
    public static final Path DEFAULT_FILE = Paths.get("./data/prompt-cache.json");

    public static final int DEFAULT_MAX_ENTRIES = 10_000;
    public static final int MIN_MAX_ENTRIES = 1;
    public static final int MAX_MAX_ENTRIES = 1_000_000;

    public static final Duration DEFAULT_TTL = Duration.ofSeconds(604_800L);
    public static final Duration MIN_TTL = Duration.ofSeconds(1L);
    public static final Duration MAX_TTL = Duration.ofSeconds(31_536_000L);

    private Path file = DEFAULT_FILE;
    private int maxEntries = DEFAULT_MAX_ENTRIES;
    private Duration ttl = DEFAULT_TTL;

    /**
     * Clamps out-of-range values to their documented defaults, logging each rejection at WARN, so the
     * application starts with safe values instead of failing to bind (Requirement 5.3).
     */
    @PostConstruct
    public void normalize() {
        if (file == null) {
            log.warn("prompt-cache.file not set; using default {}", DEFAULT_FILE);
            file = DEFAULT_FILE;
        }
        if (maxEntries < MIN_MAX_ENTRIES || maxEntries > MAX_MAX_ENTRIES) {
            log.warn(
                    "prompt-cache.max-entries {} out of range [{}, {}]; using default {}",
                    maxEntries, MIN_MAX_ENTRIES, MAX_MAX_ENTRIES, DEFAULT_MAX_ENTRIES);
            maxEntries = DEFAULT_MAX_ENTRIES;
        }
        if (ttl == null || ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
            log.warn(
                    "prompt-cache.ttl {} out of range [{}, {}]; using default {}",
                    ttl, MIN_TTL, MAX_TTL, DEFAULT_TTL);
            ttl = DEFAULT_TTL;
        }
    }

    public Path getFile() {
        return file;
    }

    public void setFile(Path file) {
        this.file = file;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
}
