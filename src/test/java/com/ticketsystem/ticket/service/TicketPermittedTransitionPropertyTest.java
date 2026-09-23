package com.ticketsystem.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.mapper.CommentMapper;
import com.ticketsystem.ticket.dto.mapper.TicketMapper;
import com.ticketsystem.ticket.dto.response.TicketDetailResponse;
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
 * Property-based test for the status-transition success path (Property 6; Req 8.2).
 *
 * <p>The complement of {@link TicketRejectedTransitionPropertyTest}: where that test proves a
 * rejected transition touches nothing, this one proves an accepted transition touches exactly what it
 * should. Over every one of the five permitted {@code (source, target)} pairs — OPEN→IN_PROGRESS,
 * OPEN→CANCELLED, IN_PROGRESS→RESOLVED, IN_PROGRESS→CANCELLED, RESOLVED→CLOSED —
 * {@link TicketStatusTransitionService#transitionStatus} must set the status to exactly the requested
 * target, advance {@code updatedAt}, leave every other field untouched, and return a response
 * reporting the new status.
 *
 * <p>The repository is the only mocked collaborator. {@code findWithCommentsById} returns the loaded
 * ticket and {@code save} echoes its argument back, so the assertions run against the very entity the
 * service mutated and the returned DTO reflects that same instance.
 *
 * <p>jqwik builds a fresh instance per try and does not honour the Jupiter/Mockito extensions, so the
 * mock and the service are wired up by hand in the constructor, matching {@link
 * TicketRejectedTransitionPropertyTest}. The stored {@code updatedAt} is fixed strictly before the
 * clock's instant so a correct implementation always advances it to a value the equality assertion can
 * distinguish from the original.
 */
class TicketPermittedTransitionPropertyTest {

    private static final Instant NOW = Instant.parse("2026-09-05T10:15:30Z");
    /** Strictly before {@link #NOW} so a correct transition advances {@code updatedAt} to {@code NOW}. */
    private static final Instant BEFORE_NOW = NOW.minusSeconds(60);
    private static final String ACTOR = "alice";

    /**
     * The five permitted transitions, spelled out independently of production code so the generator
     * exercises exactly the pairs Requirement 8.1 permits rather than trusting the production table to
     * report them.
     */
    private static final Set<Tuple2<TicketStatus, TicketStatus>> PERMITTED_PAIRS =
            Set.of(
                    Tuple.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS),
                    Tuple.of(TicketStatus.OPEN, TicketStatus.CANCELLED),
                    Tuple.of(TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED),
                    Tuple.of(TicketStatus.IN_PROGRESS, TicketStatus.CANCELLED),
                    Tuple.of(TicketStatus.RESOLVED, TicketStatus.CLOSED));

    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final TicketStatusTransitionService transitionService;

    TicketPermittedTransitionPropertyTest() {
        this.transitionService =
                new TicketStatusTransitionServiceImpl(
                        ticketRepository,
                        new TicketMapper(new CommentMapper()),
                        new DefaultTicketAuthorizationService(),
                        new TicketStatusTransitionValidator(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // Feature: support-ticket-management, Property 6: A permitted transition applies exactly the
    // requested status
    /**
     * Property 6: for every permitted {@code (source, target)} pair, requesting the transition applies
     * exactly the requested status and nothing else. Generation is exhaustive over the five permitted
     * pairs while the base ticket (priority, assignee, version) varies independently.
     *
     * <p>For each permitted pair the service must:
     *
     * <ul>
     *   <li>set the ticket's {@code status} to exactly the requested target;
     *   <li>advance {@code updatedAt} strictly past its previous value;
     *   <li>leave every other field — {@code id}, {@code title}, {@code description}, {@code priority},
     *       {@code assignee}, {@code createdAt} — unchanged;
     *   <li>persist the change and return a response reporting the new status.
     * </ul>
     *
     * <p><strong>Validates: Requirements 8.2</strong>
     */
    @Property(tries = 400)
    void permittedTransitionAppliesExactlyTheRequestedStatus(
            @ForAll("permittedPairs") Tuple2<TicketStatus, TicketStatus> pair,
            @ForAll("baseTickets") TicketState base) {

        TicketStatus current = pair.get1();
        TicketStatus target = pair.get2();

        UUID id = UUID.randomUUID();
        Ticket stored = base.toEntity(id, current);
        long storedVersion = stored.getVersion();
        String originalTitle = stored.getTitle();
        String originalDescription = stored.getDescription();
        TicketPriority originalPriority = stored.getPriority();
        String originalAssignee = stored.getAssignee();
        Instant originalCreatedAt = stored.getCreatedAt();
        Instant originalUpdatedAt = stored.getUpdatedAt();

        // jqwik reuses one test instance across all tries, so the shared mock accumulates invocations
        // from earlier tries. Clear them here so the save verification below is scoped to this try.
        clearInvocations(ticketRepository);

        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.of(stored));
        given(ticketRepository.save(any(Ticket.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // The supplied version matches the stored one, so the version check passes and the transition
        // is applied purely on the strength of the table permitting the pair.
        TicketDetailResponse response =
                transitionService.transitionStatus(id, target, storedVersion, ACTOR);

        assertThat(stored.getStatus())
                .as("status must become exactly the requested target on %s -> %s", current, target)
                .isEqualTo(target);
        assertThat(stored.getUpdatedAt())
                .as("updatedAt must strictly advance on a permitted transition %s -> %s", current, target)
                .isAfter(originalUpdatedAt);

        // Everything other than status and updatedAt is left exactly as loaded.
        assertThat(stored.getId()).isEqualTo(id);
        assertThat(stored.getTitle()).isEqualTo(originalTitle);
        assertThat(stored.getDescription()).isEqualTo(originalDescription);
        assertThat(stored.getPriority()).isEqualTo(originalPriority);
        assertThat(stored.getAssignee()).isEqualTo(originalAssignee);
        assertThat(stored.getCreatedAt()).isEqualTo(originalCreatedAt);

        // The change is persisted and the returned response reports the new status.
        verify(ticketRepository, times(1)).save(any(Ticket.class));
        assertThat(response.status())
                .as("response must report the new status on %s -> %s", current, target)
                .isEqualTo(target);
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.updatedAt())
                .as("response updatedAt must match the advanced value")
                .isEqualTo(stored.getUpdatedAt());
    }

    /**
     * Exactly the five permitted {@code (source, target)} pairs, spelled out above independently of
     * production code so the property drives the success path over precisely the transitions
     * Requirement 8.1 permits.
     */
    @Provide
    Arbitrary<Tuple2<TicketStatus, TicketStatus>> permittedPairs() {
        return Arbitraries.of(PERMITTED_PAIRS);
    }

    /**
     * Base tickets spanning every priority, an assigned and an unassigned starting point, and a version
     * drawn from a small range. The {@code current} status is supplied by the property so the generator
     * varies every other field independently of the pair under test.
     *
     * <p>{@code updatedAt} is fixed at {@link #BEFORE_NOW}, strictly before the fixed clock, so a
     * correct transition advances it to {@link #NOW} and the strictly-after assertion has a value to
     * distinguish.
     */
    @Provide
    Arbitrary<TicketState> baseTickets() {
        Arbitrary<String> startingAssignee =
                Arbitraries.frequencyOf(
                        Tuple.of(1, Arbitraries.just((String) null)),
                        Tuple.of(3, Arbitraries.of("alice", "bob", "carol")));

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
     * supplied {@code current} status for the mocked load, with {@code createdAt} and {@code updatedAt}
     * both set to {@link #BEFORE_NOW}.
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
            ticket.setCreatedAt(BEFORE_NOW);
            ticket.setUpdatedAt(BEFORE_NOW);
            ticket.setVersion(version);
            return ticket;
        }
    }
}
