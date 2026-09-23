package com.ticketsystem.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.ticket.config.UserDirectoryProperties;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Directory membership and its binding from configuration (Requirement 4.5). */
class ConfiguredUserDirectoryTest {

    @Test
    void shouldRecogniseConfiguredUsersExactly() {
        UserDirectory directory = directoryFrom("alice,bob");

        assertThat(directory.exists("alice")).isTrue();
        assertThat(directory.exists("bob")).isTrue();
        assertThat(directory.exists("dave")).isFalse();
    }

    @Test
    void shouldTreatIdentifiersAsCaseSensitive() {
        // An assignee identifier is an identity; matching "Alice" to "alice" would attribute the
        // ticket to a user the caller did not name.
        UserDirectory directory = directoryFrom("alice");

        assertThat(directory.exists("ALICE")).isFalse();
        assertThat(directory.exists("Alice")).isFalse();
    }

    @Test
    void shouldNeverResolveAbsentOrBlankIdentifiers() {
        UserDirectory directory = directoryFrom("alice");

        assertThat(directory.exists(null)).isFalse();
        assertThat(directory.exists("")).isFalse();
        assertThat(directory.exists("   ")).isFalse();
    }

    @Test
    void shouldKnowNoOneWhenConfigurationIsEmpty() {
        // The shipped default resolves to an empty value, so an unconfigured environment must bind
        // to an empty directory rather than failing to start or accepting any string.
        UserDirectory directory = directoryFrom("");

        assertThat(directory.exists("alice")).isFalse();
    }

    @Test
    void shouldIgnoreLaterMutationOfTheBoundProperties() {
        UserDirectoryProperties properties = new UserDirectoryProperties();
        properties.setKnownUsers(Set.of("alice"));
        UserDirectory directory = new ConfiguredUserDirectory(properties);

        properties.setKnownUsers(Set.of("dave"));

        assertThat(directory.exists("alice")).isTrue();
        assertThat(directory.exists("dave")).isFalse();
    }

    /** Binds through the real relaxed binder so the yaml-shaped value is what gets exercised. */
    private static UserDirectory directoryFrom(String configuredValue) {
        Binder binder =
                new Binder(
                        new MapConfigurationPropertySource(
                                Map.of("ticket.user-directory.known-users", configuredValue)));

        UserDirectoryProperties properties =
                binder.bindOrCreate("ticket.user-directory", UserDirectoryProperties.class);

        return new ConfiguredUserDirectory(properties);
    }
}
