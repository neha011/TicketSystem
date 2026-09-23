package com.ticketsystem.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.mapper.CommentMapper;
import com.ticketsystem.ticket.dto.mapper.TicketMapper;
import com.ticketsystem.ticket.exception.ConflictException;
import com.ticketsystem.ticket.repository.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.Tuple.Tuple2;

/**
 * Property-based test for the status-transition rejection path (Property 5; Req 8.3, 8.4, 8.5, 8.6).
 *
 * <p>Over the full {@code TicketStatus × TicketStatus} cross product with the five permitted pairs
 * assumed away — that is, all 20 non-permitted pairs, including the five self-transitions and every
 * transition out of a terminal state — {@link TicketStatusTransitionService#transitionStatus} must
 * throw {@link ConflictException} (which maps to 409) and touch nothing: the loaded ticket's {@code
 * status}, {@code updatedAt}, and {@code version} are byte-for-byte what they were before the call,
 * and {@code save} is never invoked. This is the service-level counterpart to the pure-validator
 * rejection tests: it proves the ordering in the service (validate before mutate) actually keeps a
 * rejected transition from dirtying the entity or reaching persistence.
 *
 * <p>The repository is the only mocked collaborator. {@code findWithCommentsById} returns the loaded
 * ticket and {@code save} echoes its argument back, so "the ticket is unchanged" is asserted against
 * the very entity instance the service operates on rather than against stubbed returns.
 *
 * <p>jqwik builds a fresh instance per try and does not honour the Jupiter/Mockito extensions, so the
 * mock and the service are wired up by hand in the constructor, matching {@link
 * TicketVersionMismatchPropertyTest}. The expected version supplied to the service always equals the
 * stored version so the version check passes and the transition table is the sole reason for the
 * rejection.
 */
class TicketRejectedTransitionPropertyTest {

    private static final Instant NOW = Instant.parse("2026-09-05T10:15:30Z");
    private static final String ACTOR = "alice";

    /**
     * The permitted transitions, spelled out independently of production code so the test does not
     * assume away a pair the production table wrongly permits.
     */
    private static final Set<String> PERMITTED_PAIRS =
            Set.of(
                    "OPEN->IN_PROGRESS",
                    "OPEN->CANCELLED",
                    "IN_PROGRESS->RESOLVED",
                    "IN_PROGRESS->CANCELLED",
                    "RESOLVED->CLOSED");

    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final TicketStatusTransitionService transitionService;

    TicketRejectedTransitionPropertyTest() {
        this.transitionService =
                new TicketStatusTransitionServiceImpl(
                        ticketRepository,
                        new TicketMapper(new CommentMapper()),
                        new DefaultTicketAuthorizationService(),
                        new TicketStatusTransitionValidator(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // Feature: support-ticket-management, Property 5: A rejected transition leaves the ticket unchanged
    /**
     * Property 5: for every non-permitted {@code (current, target)} pair, requesting the transition is
     * a 409 conflict that mutates nothing. Generation is exhaustive over the full 25-pair cross product
     * with the 5 permitted pairs assumed away, so all 20 rejected pairs — the five self-transitions and
     * every transition out of a terminal state included — are checked on every run.
     *
     * <p>For each rejected pair the service must:
     *
     * <ul>
     *   <li>throw a {@link ConflictException} that maps to 409;
     *   <li>leave the loaded ticket's {@code status}, {@code updatedAt}, and {@code version} exactly as
     *       they were before the call;
     *   <li>never invoke {@code save}.
     * </ul>
     *
     * <p>The rejected pair is drawn from a generator restricted to exactly the 20 non-permitted pairs,
     * so every try exercises a genuine rejection; with 400 tries all 20 pairs — the five
     * self-transitions and every transition out of a terminal state included — are hit many times over
     * while the base ticket (priority, assignee, version) varies independently.
     *
     * <p><strong>Validates: Requirements 8.3, 8.4, 8.5, 8.6</strong>
     */
    @Property(tries = 400)
    void rejectedTransitionThrowsConflictAndMutatesNothing(
            @ForAll("rejectedPairs") Tuple2<TicketStatus, TicketStatus> pair,
            @ForAll("baseTickets") TicketState base) {

        TicketStatus current = pair.get1();
        TicketStatus target = pair.get2();

        UUID id = UUID.randomUUID();
        Ticket stored = base.toEntity(id, current);
        long storedVersion = stored.getVersion();
        Instant storedUpdatedAt = stored.getUpdatedAt();

        // jqwik reuses one test instance across all tries, so the shared mock accumulates invocations
        // from earlier tries. Clear them here so the save verification below is scoped to this try.
        clearInvocations(ticketRepository);

        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.of(stored));
        given(ticketRepository.save(any(Ticket.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // The supplied version matches the stored one, so the version check passes and the transition
        // table is the sole reason for the rejection.
        assertThatThrownBy(() -> transitionService.transitionStatus(id, target, storedVersion, ACTOR))
                .as("rejected transition %s -> %s must be a 409 conflict", current, target)
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).status().value())
                .isEqualTo(409);

        assertThat(stored.getStatus())
                .as("status must be untouched on a rejected transition %s -> %s", current, target)
                .isEqualTo(current);
        assertThat(stored.getUpdatedAt())
                .as("updatedAt must be untouched on a rejected transition %s -> %s", current, target)
                .isEqualTo(storedUpdatedAt);
        assertThat(stored.getVersion())
                .as("version must be untouched on a rejected transition %s -> %s", current, target)
                .isEqualTo(storedVersion);

        // No write reaches the repository when the transition is rejected.
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    /**
     * Exactly the 20 non-permitted {@code (current, target)} pairs: the full {@code TicketStatus ×
     * TicketStatus} cross product with the five permitted pairs filtered out. The permitted set is
     * spelled out above independently of production code, so a table that wrongly permits a pair would
     * leave that pair in this generator and the property would then catch the service accepting it.
     */
    @Provide
    Arbitrary<Tuple2<TicketStatus, TicketStatus>> rejectedPairs() {
        return Arbitraries.of(TicketStatus.class)
                .flatMap(
                        current ->
                                Arbitraries.of(TicketStatus.class)
                                        .map(target -> Tuple.of(current, target)))
                .filter(pair -> !PERMITTED_PAIRS.contains(pair.get1().name() + "->" + pair.get2().name()));
    }

    /**
     * Base tickets spanning every priority, an assigned and an unassigned starting point, and a version
     * drawn from a small range. The {@code current} status is supplied by the property so the generator
     * varies every other field independently of the pair under test.
     *
     * <p>{@code updatedAt} is fixed at {@link #NOW} so it equals the fixed clock: if the service ever
     * mutated the entity on a rejected path, the same-tick nudge would push {@code updatedAt} past
     * {@link #NOW} and the equality assertion would catch it.
     */
    @Provide
    Arbitrary<TicketState> baseTickets() {
        Arbitrary<String> startingAssignee =
                Arbitraries.frequencyOf(
                        net.jqwik.api.Tuple.of(1, Arbitraries.just((String) null)),
                        net.jqwik.api.Tuple.of(3, Arbitraries.of("alice", "bob", "carol")));

        return Combinators.combine(
                        Arbitraries.integers().between(0, 999),
                        Arbitraries.of(TicketPriority.class),
                        startingAssignee,
                        Arbitraries.longs().between(0L, 8L))
                .as(
                        (serial, priority, assignee, version) ->
                                new TicketState(
                                        "Original title " + serial,
                                        "Original description " + serial,
                                        priority,
                                        assignee,
                                        version));
    }

    /**
     * The pre-transition state of a ticket, minus its status. {@link #toEntity} materializes it in the
     * supplied {@code current} status for the mocked load.
     */
    record TicketState(
            String title,
            String description,
            TicketPriority priority,
            String assignee,
            long version) {

        Ticket toEntity(UUID id, TicketStatus status) {
            Ticket ticket = new Ticket();
            ticket.setId(id);
            ticket.setTitle(title);
            ticket.setDescription(description);
            ticket.setStatus(status);
            ticket.setPriority(priority);
            ticket.setAssignee(assignee);
            ticket.setCreatedAt(NOW);
            ticket.setUpdatedAt(NOW);
            ticket.setVersion(version);
            return ticket;
        }
    }
}
