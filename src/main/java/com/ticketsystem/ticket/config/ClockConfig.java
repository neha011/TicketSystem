package com.ticketsystem.ticket.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock services read the current time from.
 *
 * <p>Exposed as a bean rather than calling {@code Instant.now()} inline so creation and update
 * timestamps come from an injectable source: a test can then pin the instant and assert the exact
 * stored value instead of asserting "close enough to now", and the timestamp ordering properties
 * become checkable rather than timing-dependent.
 *
 * <p>Fixed to UTC. Timestamps are persisted as {@code Instant} and serialized with a {@code Z}
 * offset, so the host's default zone must not be able to influence them.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
