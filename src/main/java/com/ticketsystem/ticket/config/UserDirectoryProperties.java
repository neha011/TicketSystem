package com.ticketsystem.ticket.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Identities that may be named as a ticket assignee (Requirement 4.5).
 *
 * <p>Grouped as typed properties rather than scattered {@code @Value} injections, per the Spring Boot
 * steering doc. The list is the directory's whole contents: an empty list means the directory knows
 * no one, so every submitted assignee is rejected with 422. That is the deliberate fail-closed
 * choice — a misconfigured environment refuses assignments rather than accepting any string.
 *
 * <p>These identifiers are usernames, not secrets, so they are safe to hold in configuration. When a
 * real identity provider or user table replaces this, only {@code ConfiguredUserDirectory} changes.
 */
@Component
@ConfigurationProperties(prefix = "ticket.user-directory")
public class UserDirectoryProperties {

    private Set<String> knownUsers = Set.of();

    public Set<String> getKnownUsers() {
        return knownUsers;
    }

    /** Defensive copy: the directory must not be mutable after binding. */
    public void setKnownUsers(Set<String> knownUsers) {
        this.knownUsers = knownUsers == null ? Set.of() : Set.copyOf(knownUsers);
    }
}
