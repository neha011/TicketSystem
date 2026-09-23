package com.ticketsystem.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.exception.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Example-based tests for {@link TicketStatusTransitionValidator}.
 *
 * <p>Companion to {@code TicketStatusTransitionValidatorPropertyTest}: the property test asserts the
 * biconditional against a reference table, while this class spells out every one of the 25
 * {@code (current, target)} pairs literally. Writing the pairs out by hand means a reviewer can read
 * the state machine straight off the test source, and a change to the production table shows up here
 * as a named failing case rather than as a reference set that was edited to match.
 *
 * <p><strong>Requirements: 8.1, 8.3, 8.4, 8.5, 8.6</strong>
 */
class TicketStatusTransitionValidatorTest {

    private final TicketStatusTransitionValidator validator = new TicketStatusTransitionValidator();

    @Nested
    @DisplayName("permitted transitions (Requirement 8.1)")
    class PermittedTransitions {

        /** The five transitions listed in Requirement 8.1, one case each. */
        @ParameterizedTest(name = "{0} -> {1} is permitted")
        @CsvSource({
            "OPEN,        IN_PROGRESS",
            "OPEN,        CANCELLED",
            "IN_PROGRESS, RESOLVED",
            "IN_PROGRESS, CANCELLED",
            "RESOLVED,    CLOSED"
        })
        void shouldAcceptEachPermittedTransition(TicketStatus current, TicketStatus target) {

            boolean permitted = validator.isPermitted(current, target);

            assertThat(permitted).isTrue();
            assertThatCode(() -> validator.requirePermitted(current, target)).doesNotThrowAnyException();
        }

        @Test
        void shouldReportTheExactPermittedTargetsOfEachSourceStatus() {

            assertThat(validator.permittedTargets(TicketStatus.OPEN))
                    .containsExactlyInAnyOrder(TicketStatus.IN_PROGRESS, TicketStatus.CANCELLED);
            assertThat(validator.permittedTargets(TicketStatus.IN_PROGRESS))
                    .containsExactlyInAnyOrder(TicketStatus.RESOLVED, TicketStatus.CANCELLED);
            assertThat(validator.permittedTargets(TicketStatus.RESOLVED))
                    .containsExactly(TicketStatus.CLOSED);
            assertThat(validator.permittedTargets(TicketStatus.CLOSED)).isEmpty();
            assertThat(validator.permittedTargets(TicketStatus.CANCELLED)).isEmpty();
        }
    }

    @Nested
    @DisplayName("rejected transitions (Requirements 8.3, 8.4, 8.5, 8.6)")
    class RejectedTransitions {

        /**
         * All 20 pairs outside Requirement 8.1, listed one by one and grouped by source status so the
         * coverage is verifiable by eye: 3 from OPEN, 3 from IN_PROGRESS, 4 from RESOLVED, and all 5
         * from each terminal state.
         */
        @ParameterizedTest(name = "{0} -> {1} is rejected")
        @CsvSource({
            // from OPEN: only IN_PROGRESS and CANCELLED are permitted
            "OPEN,        OPEN",
            "OPEN,        RESOLVED",
            "OPEN,        CLOSED",
            // from IN_PROGRESS: only RESOLVED and CANCELLED are permitted
            "IN_PROGRESS, OPEN",
            "IN_PROGRESS, IN_PROGRESS",
            "IN_PROGRESS, CLOSED",
            // from RESOLVED: only CLOSED is permitted
            "RESOLVED,    OPEN",
            "RESOLVED,    IN_PROGRESS",
            "RESOLVED,    RESOLVED",
            "RESOLVED,    CANCELLED",
            // from CLOSED: terminal, nothing is permitted
            "CLOSED,      OPEN",
            "CLOSED,      IN_PROGRESS",
            "CLOSED,      RESOLVED",
            "CLOSED,      CLOSED",
            "CLOSED,      CANCELLED",
            // from CANCELLED: terminal, nothing is permitted
            "CANCELLED,   OPEN",
            "CANCELLED,   IN_PROGRESS",
            "CANCELLED,   RESOLVED",
            "CANCELLED,   CLOSED",
            "CANCELLED,   CANCELLED"
        })
        void shouldRejectEachTransitionOutsideTheAllowedTable(
                TicketStatus current, TicketStatus target) {

            boolean permitted = validator.isPermitted(current, target);
            Throwable thrown = catchThrowable(() -> validator.requirePermitted(current, target));

            assertThat(permitted).isFalse();
            assertThat(thrown)
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining(current.name())
                    .hasMessageContaining(target.name());
        }

        /**
         * Requirement 8.4 as its own case. The five self-transitions are already among the 20 rejected
         * pairs above, but they are the rule most likely to be special-cased away by an implementation
         * that treats "no change" as a harmless no-op, so they are asserted explicitly.
         */
        @ParameterizedTest(name = "{0} -> {0} is rejected")
        @EnumSource(TicketStatus.class)
        void shouldRejectATransitionToTheCurrentStatus(TicketStatus current) {

            boolean permitted = validator.isPermitted(current, current);
            Throwable thrown = catchThrowable(() -> validator.requirePermitted(current, current));

            assertThat(permitted).isFalse();
            assertThat(thrown).isInstanceOf(ConflictException.class).hasMessageContaining(current.name());
        }

        /** Requirement 8.5: OPEN is a creation state, so no source status may return to it. */
        @ParameterizedTest(name = "{0} -> OPEN is rejected")
        @EnumSource(TicketStatus.class)
        void shouldRejectAnyTransitionBackToOpen(TicketStatus current) {

            assertThat(validator.isPermitted(current, TicketStatus.OPEN)).isFalse();
            assertThat(validator.permittedTargets(current)).doesNotContain(TicketStatus.OPEN);
        }

        /** Requirement 8.6: CLOSED and CANCELLED are terminal and advertise no outgoing targets. */
        @ParameterizedTest(name = "{0} is terminal")
        @EnumSource(
                value = TicketStatus.class,
                names = {"CLOSED", "CANCELLED"})
        void shouldTreatClosedAndCancelledAsTerminal(TicketStatus terminal) {

            assertThat(terminal.isTerminal()).isTrue();
            assertThat(validator.permittedTargets(terminal)).isEmpty();
        }
    }
}
