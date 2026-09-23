package com.ticketsystem.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.ticketsystem.ticket.config.UserDirectoryProperties;
import com.ticketsystem.ticket.exception.UnprocessableEntityException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/** Assignee existence checking (Requirement 4.5). */
class DirectoryBackedAssigneeValidatorTest {

    private AssigneeValidator validator;

    @BeforeEach
    void setUp() {
        UserDirectoryProperties properties = new UserDirectoryProperties();
        properties.setKnownUsers(Set.of("alice", "bob"));
        validator = new DirectoryBackedAssigneeValidator(new ConfiguredUserDirectory(properties));
    }

    @Test
    void shouldAcceptKnownUser() {
        assertThatCode(() -> validator.requireExists("alice")).doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptNullAssigneeAsUnassigned() {
        // Null means unassigned on create and "clear the assignee" on update; neither is a failure.
        assertThatCode(() -> validator.requireExists(null)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "rejects [{0}]")
    @ValueSource(strings = {"dave", "", "   ", "ALICE", "Alice", " alice", "alice "})
    void shouldRejectValuesNamingNoUser(String assignee) {
        UnprocessableEntityException thrown =
                catchThrowableOfType(
                        () -> validator.requireExists(assignee), UnprocessableEntityException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void shouldReportAssigneeAsTheFailingField() {
        UnprocessableEntityException thrown =
                catchThrowableOfType(
                        () -> validator.requireExists("dave"), UnprocessableEntityException.class);

        assertThat(thrown.fieldErrors()).hasSize(1);
        assertThat(thrown.fieldErrors().get(0).field()).isEqualTo("assignee");
        assertThat(thrown.fieldErrors().get(0).reason()).isNotBlank();
    }

    @Test
    void shouldRejectEveryAssigneeWhenDirectoryIsEmpty() {
        // Fail closed: a misconfigured directory refuses assignments rather than accepting anything.
        AssigneeValidator emptyDirectoryValidator =
                new DirectoryBackedAssigneeValidator(
                        new ConfiguredUserDirectory(new UserDirectoryProperties()));

        assertThat(
                        catchThrowableOfType(
                                () -> emptyDirectoryValidator.requireExists("alice"),
                                UnprocessableEntityException.class))
                .isNotNull();
        assertThatCode(() -> emptyDirectoryValidator.requireExists(null)).doesNotThrowAnyException();
    }
}
