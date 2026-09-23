package com.ticketsystem.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.exception.ConflictException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for {@link TicketStatusTransitionValidator}.
 *
 * <p>The input space is the complete {@code TicketStatus × TicketStatus} cross product — 25 pairs, 5
 * permitted and 20 rejected — so generation is exhaustive rather than sampled: every pair is checked
 * on every run and nothing depends on the random seed.
 */
class TicketStatusTransitionValidatorPropertyTest {

    /**
     * The permitted transitions, spelled out independently of production code. Deliberately does not
     * read {@link TicketStatus#allowedTargets()}: reusing the production table would make the test
     * assert only that the table equals itself.
     */
    private static final Set<String> PERMITTED_PAIRS =
            Set.of(
                    "OPEN->IN_PROGRESS",
                    "OPEN->CANCELLED",
                    "IN_PROGRESS->RESOLVED",
                    "IN_PROGRESS->CANCELLED",
                    "RESOLVED->CLOSED");

    private final TicketStatusTransitionValidator validator = new TicketStatusTransitionValidator();

    // Feature: support-ticket-management, Property 1: Transition permitted if and only if in the
    // allowed table
    /**
     * Property 1: for every one of the 25 {@code (current, target)} pairs, {@code isPermitted} agrees
     * with the reference table in both directions. Asserted as a single biconditional rather than two
     * implications, so an implementation that permits too much and one that permits too little both
     * fail the same check.
     *
     * <p><strong>Validates: Requirements 8.1, 8.4</strong>
     */
    @Property(generation = GenerationMode.EXHAUSTIVE)
    void isPermittedAgreesWithTheAllowedTableOverTheEntireTransitionSpace(
            @ForAll TicketStatus current, @ForAll TicketStatus target) {

        boolean expected = PERMITTED_PAIRS.contains(current.name() + "->" + target.name());

        boolean actual = validator.isPermitted(current, target);

        assertThat(actual)
                .as("isPermitted(%s, %s) should be %s", current, target, expected)
                .isEqualTo(expected);
    }

    // Feature: support-ticket-management, Property 2: No transition out of a terminal state is ever
    // permitted
    /**
     * Property 2: CLOSED and CANCELLED are dead ends. For every possible target — including the other
     * terminal state and the terminal state itself — no transition out of a terminal state is
     * permitted, and the terminal state advertises no outgoing targets at all.
     *
     * <p>Both halves matter: {@code allowedTargets()} being empty is what the UI uses to decide
     * whether to offer any transition affordance, while {@code isPermitted} is what the server
     * enforces. Asserting them together prevents the affordance and the enforcement from drifting
     * apart.
     *
     * <p><strong>Validates: Requirements 8.6</strong>
     */
    @Property(generation = GenerationMode.EXHAUSTIVE)
    void noTransitionOutOfATerminalStateIsEverPermitted(
            @ForAll("terminalStatuses") TicketStatus terminal, @ForAll TicketStatus target) {

        assertThat(validator.isPermitted(terminal, target))
                .as("terminal %s should permit no transition to %s", terminal, target)
                .isFalse();

        assertThat(terminal.allowedTargets())
                .as("terminal %s should advertise no outgoing targets", terminal)
                .isEmpty();

        assertThat(validator.permittedTargets(terminal))
                .as("validator should report no permitted targets for terminal %s", terminal)
                .isEmpty();
    }

    // Feature: support-ticket-management, Property 3: OPEN is unreachable after creation
    /**
     * Property 3: OPEN is a creation state only. For every source status — including OPEN itself — no
     * transition back into OPEN is permitted, so a ticket that has left OPEN can never re-enter it.
     * Covering all five sources subsumes the specific RESOLVED/CLOSED/CANCELLED → OPEN cases called
     * out in the requirement.
     *
     * <p>Also asserts that OPEN never appears in any source's advertised targets, so the transition
     * affordance the UI derives from the table cannot offer a reopen that the server would reject.
     *
     * <p><strong>Validates: Requirements 8.5</strong>
     */
    @Property(generation = GenerationMode.EXHAUSTIVE)
    void openIsNeverAPermittedTransitionTarget(@ForAll TicketStatus current) {

        assertThat(validator.isPermitted(current, TicketStatus.OPEN))
                .as("%s should not be permitted to transition back to OPEN", current)
                .isFalse();

        assertThat(current.allowedTargets())
                .as("%s should not advertise OPEN as a target", current)
                .doesNotContain(TicketStatus.OPEN);

        assertThat(validator.permittedTargets(current))
                .as("validator should not report OPEN among the permitted targets of %s", current)
                .doesNotContain(TicketStatus.OPEN);
    }

    // Feature: support-ticket-management, Property 4: Reachable status sequences never skip a required
    // state
    /**
     * Property 4: the shape of the whole lifecycle, not just individual steps. A ticket starts at OPEN
     * and is offered an arbitrary sequence of up to 12 requested targets; rejected requests are
     * ignored, exactly as the service layer ignores them, so the walk only ever follows permitted
     * steps.
     *
     * <p>Every status the walk actually reaches must have been reached legitimately: RESOLVED only
     * after IN_PROGRESS was occupied earlier, and CLOSED only after IN_PROGRESS and then RESOLVED were
     * occupied earlier in that order. The single steps that would skip a state — OPEN→RESOLVED,
     * OPEN→CLOSED, IN_PROGRESS→CLOSED — must never be realized by any step of any walk.
     *
     * <p>A per-pair check cannot express this: an implementation whose reference table was broadened
     * to include a skipping edge would still satisfy a biconditional written against that same
     * broadened table, while this property pins the ordering that the lifecycle itself requires.
     *
     * <p><strong>Validates: Requirements 8.1, 8.5, 8.6</strong>
     */
    @Property(tries = 500)
    void reachableStatusSequencesNeverSkipARequiredState(
            @ForAll("transitionRequestSequences") List<TicketStatus> requestedTargets) {

        TicketStatus current = TicketStatus.OPEN;
        List<TicketStatus> trace = new ArrayList<>();
        trace.add(current);

        for (TicketStatus requested : requestedTargets) {
            if (!validator.isPermitted(current, requested)) {
                continue;
            }

            assertThat(current.name() + "->" + requested.name())
                    .as("no permitted step may skip a required state")
                    .isNotIn("OPEN->RESOLVED", "OPEN->CLOSED", "IN_PROGRESS->CLOSED");

            current = requested;
            trace.add(current);
        }

        assertThat(trace).as("every walk starts at OPEN").startsWith(TicketStatus.OPEN);

        int firstInProgress = trace.indexOf(TicketStatus.IN_PROGRESS);
        int firstResolved = trace.indexOf(TicketStatus.RESOLVED);
        int firstClosed = trace.indexOf(TicketStatus.CLOSED);

        if (firstResolved >= 0) {
            assertThat(firstInProgress)
                    .as("RESOLVED in trace %s requires IN_PROGRESS to have been occupied earlier", trace)
                    .isBetween(0, firstResolved - 1);
        }

        if (firstClosed >= 0) {
            assertThat(firstInProgress)
                    .as("CLOSED in trace %s requires IN_PROGRESS to have been occupied earlier", trace)
                    .isBetween(0, firstClosed - 1);
            assertThat(firstResolved)
                    .as("CLOSED in trace %s requires RESOLVED to have been occupied earlier", trace)
                    .isBetween(firstInProgress + 1, firstClosed - 1);
        }
    }

    // Feature: support-ticket-management, Property 8: Rejection messages name both the current and
    // requested status
    /**
     * Property 8: every rejection carries enough information for the UI to report it verbatim. For all
     * 20 non-permitted pairs, {@code requirePermitted} throws a {@link ConflictException} whose message
     * names both the current status and the requested target status.
     *
     * <p>Generation is exhaustive over the full cross product with the 5 permitted pairs assumed away,
     * so every rejected pair — including the five self-transitions and every transition out of a
     * terminal state — is covered on every run.
     *
     * <p>The requirement is that the UI displays both statuses (Requirement 8.7) without inventing the
     * text, so the message is also asserted to be a single self-contained sentence: non-blank, and
     * naming the current status ahead of the requested one for pairs where the two differ, so a
     * reversed message cannot pass a mere containment check.
     *
     * <p><strong>Validates: Requirements 8.7</strong>
     */
    @Property(generation = GenerationMode.EXHAUSTIVE)
    void rejectionMessagesNameBothTheCurrentAndTheRequestedStatus(
            @ForAll TicketStatus current, @ForAll TicketStatus target) {

        Assume.that(!PERMITTED_PAIRS.contains(current.name() + "->" + target.name()));

        Throwable thrown = catchThrowable(() -> validator.requirePermitted(current, target));

        assertThat(thrown)
                .as("requirePermitted(%s, %s) should reject with a ConflictException", current, target)
                .isInstanceOf(ConflictException.class);

        String message = thrown.getMessage();

        assertThat(message)
                .as("rejection message for %s -> %s should name both statuses", current, target)
                .isNotBlank()
                .contains(current.name())
                .contains(target.name());

        if (current != target) {
            assertThat(message.indexOf(current.name()))
                    .as(
                            "message '%s' should name the current status %s before the requested status %s",
                            message, current, target)
                    .isLessThan(message.indexOf(target.name()));
        }
    }

    /**
     * Requested transition targets as a ticket's caller would submit them: any status at all, valid or
     * not, in any order, including the empty sequence. Deliberately unfiltered — feeding only
     * permitted targets would test the walk against itself rather than against the table.
     *
     * <p>Length 12 comfortably exceeds the four-step longest path from OPEN, so sequences routinely
     * continue past a terminal state and past rejected requests.
     */
    @Provide
    Arbitrary<List<TicketStatus>> transitionRequestSequences() {
        return Arbitraries.of(TicketStatus.class).list().ofMinSize(0).ofMaxSize(12);
    }

    /**
     * The terminal states, spelled out rather than derived from {@link TicketStatus#isTerminal()}: a
     * production bug that made a state non-terminal would otherwise silently shrink this generator to
     * nothing and the property would pass vacuously.
     */
    @Provide
    Arbitrary<TicketStatus> terminalStatuses() {
        return Arbitraries.of(TicketStatus.CLOSED, TicketStatus.CANCELLED);
    }
}
