package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.config.UserDirectoryProperties;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * {@link UserDirectory} backed by the configured set of known usernames.
 *
 * <p>Membership is exact and case-sensitive: an assignee identifier is an identity, and quietly
 * matching {@code "Alice"} to {@code "alice"} would attribute a ticket to a user the caller did not
 * name. Blank and null identifiers are never members, so whitespace can never resolve to a user.
 */
@Service
public class ConfiguredUserDirectory implements UserDirectory {

    private final Set<String> knownUsers;

    public ConfiguredUserDirectory(UserDirectoryProperties properties) {
        this.knownUsers = Set.copyOf(properties.getKnownUsers());
    }

    @Override
    public boolean exists(String userId) {
        return userId != null && !userId.isBlank() && knownUsers.contains(userId);
    }
}
