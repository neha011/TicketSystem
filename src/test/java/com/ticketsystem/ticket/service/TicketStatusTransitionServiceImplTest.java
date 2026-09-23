package com.ticketsystem.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.mapper.CommentMapper;
import com.ticketsystem.ticket.dto.mapper.TicketMapper;
import com.ticketsystem.ticket.exception.ConflictException;
import com.ticketsystem.ticket.exception.ForbiddenException;
import com.ticketsystem.ticket.exception.NotFoundException;
import com.ticketsystem.ticket.repository.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Named-example unit tests for {@link TicketStatusTransitionService} error paths (Requirements 3.5,
 * 4.4, 8.5, 8.9).
 *
 * <p>Where the two property tests cover the transition table exhaustively — a rejected transition
 * touching nothing ({@link TicketRejectedTransitionPropertyTest}) and a permitted one applying
 * exactly the requested status ({@link TicketPermittedTransitionPropertyTest}) — these example tests
 * pin down the specific error responses the service must produce: the never-return-to-OPEN rule as a
 * 409 from every source status (Requirement 8.5), a missing ticket as a 404 (Requirement 4.4), an
 * unauthorized actor as a 403 (Requirement 3.5), and an optimistic-lock failure on flush surfacing as
 * a conflict (Requirement 8.9).
 *
 * <p>The repository is the only mocked collaborator, matching {@link TicketServiceImplTest}. The
 * mapper, authorization policy, and transition validator are the real implementations so the
 * assertions reflect the behaviour a caller actually gets rather than stubbed returns. The clock is
 * fixed so timestamps are deterministic.
 */
@ExtendWith(MockitoExtension.class)
class TicketStatusTransitionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-05T10:15:30Z");
    private static final String ACTOR = "alice";

    @Mock private TicketRepository ticketRepository;

    /** Only wired in by the one test that needs a policy which refuses an identified actor. */
    @Mock private TicketAuthorizationService authorizationPolicy;

    private TicketStatusTransitionService transitionService;

    @BeforeEach
    void setUp() {
        transitionService = serviceWithPolicy(new DefaultTicketAuthorizationService());
    }

    private TicketStatusTransitionService serviceWithPolicy(TicketAuthorizationService policy) {
        return new TicketStatusTransitionServiceImpl(
                ticketRepository,
                new TicketMapper(new CommentMapper()),
                policy,
                new TicketStatusTransitionValidator(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * Req 8.5: OPEN is unreachable after creation. From every one of the five source statuses,
     * requesting a transition back to OPEN is a 409 conflict whose message names both the current and
     * the requested status (Requirement 8.7), and the entity is left untouched with no save issued.
     */
    @Test
    void shouldRejectTransitionToOpenFromEverySourceStatusAsConflict() {
        for (TicketStatus source : TicketStatus.values()) {
            UUID id = UUID.randomUUID();
            Ticket stored = storedTicket(id, source);
            given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.of(stored));

            ConflictException thrown =
                    catchThrowableOfType(
                            () ->
                                    transitionService.transitionStatus(
                                            id, TicketStatus.OPEN, stored.getVersion(), ACTOR),
                            ConflictException.class);

            assertThat(thrown).as("transition %s -> OPEN must be rejected", source).isNotNull();
            assertThat(thrown.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(thrown.clientMessage())
                    .as("409 message names both the current and requested status")
                    .contains(source.name(), TicketStatus.OPEN.name());

            // A rejected transition must not dirty the entity and must not reach persistence.
            assertThat(stored.getStatus()).isEqualTo(source);
            assertThat(stored.getUpdatedAt()).isEqualTo(NOW);
        }

        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    /**
     * Req 4.4: a transition against an identifier that names no ticket is a 404 carrying its status,
     * and no save is issued because there is no record to write.
     */
    @Test
    void shouldReturn404WhenTicketNotFound() {
        UUID id = UUID.randomUUID();
        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.empty());

        NotFoundException thrown =
                catchThrowableOfType(
                        () -> transitionService.transitionStatus(id, TicketStatus.IN_PROGRESS, 0L, ACTOR),
                        NotFoundException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(thrown.clientMessage()).contains(id.toString());
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    /**
     * Req 3.5: an unidentified actor is refused with a 403. The refusal happens after the ticket is
     * loaded but before any mutation, so nothing is written and the 403 body carries no ticket detail.
     */
    @Test
    void shouldReturn403WhenActorIsUnidentified() {
        UUID id = UUID.randomUUID();
        Ticket stored = storedTicket(id, TicketStatus.OPEN);
        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.of(stored));

        ForbiddenException thrown =
                catchThrowableOfType(
                        () ->
                                transitionService.transitionStatus(
                                        id, TicketStatus.IN_PROGRESS, stored.getVersion(), "  "),
                        ForbiddenException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(thrown.clientMessage())
                .isEqualTo(ForbiddenException.MESSAGE)
                .doesNotContain("Login fails on SSO", "502 after redirect", "bob", id.toString());

        assertThat(stored.getStatus()).isEqualTo(TicketStatus.OPEN);
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    /**
     * Req 3.5 with a policy that refuses an actor it <em>did</em> recognize, which the baseline policy
     * never does. Stubbing the gate is the only way to reach that branch; the point is that the ticket
     * was already loaded when the refusal happened, so the 403 body must still carry no ticket detail
     * and no save may be issued.
     */
    @Test
    void shouldReturn403WhenPolicyRefusesAnIdentifiedActor() {
        UUID id = UUID.randomUUID();
        Ticket stored = storedTicket(id, TicketStatus.OPEN);
        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.of(stored));
        willThrow(new ForbiddenException()).given(authorizationPolicy).requireCanModify(ACTOR, stored);

        TicketStatusTransitionService service = serviceWithPolicy(authorizationPolicy);

        ForbiddenException thrown =
                catchThrowableOfType(
                        () ->
                                service.transitionStatus(
                                        id, TicketStatus.IN_PROGRESS, stored.getVersion(), ACTOR),
                        ForbiddenException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(stored.getStatus()).isEqualTo(TicketStatus.OPEN);
        verify(ticketRepository, never()).save(any(Ticket.class));
    }

    /**
     * Req 8.9: a concurrent write detected by JPA's {@code @Version} check on flush surfaces the
     * narrow interleaving — the client's supplied version still matched the row this transaction read,
     * but another transaction bumped it before flush. The service does not swallow it; the
     * {@link ObjectOptimisticLockingFailureException} propagates so the exception handler can map it to
     * 409, rather than being reported as a successful transition.
     */
    @Test
    void shouldPropagateOptimisticLockingFailureAsConflictOnFlush() {
        UUID id = UUID.randomUUID();
        Ticket stored = storedTicket(id, TicketStatus.OPEN);
        given(ticketRepository.findWithCommentsById(id)).willReturn(Optional.of(stored));
        // The permitted OPEN -> IN_PROGRESS transition passes the version check, then loses the race on
        // flush: save raises the optimistic-locking failure that JPA would throw.
        given(ticketRepository.save(any(Ticket.class)))
                .willThrow(new ObjectOptimisticLockingFailureException(Ticket.class, id));

        Throwable thrown =
                catchThrowable(
                        () ->
                                transitionService.transitionStatus(
                                        id, TicketStatus.IN_PROGRESS, stored.getVersion(), ACTOR));

        assertThat(thrown).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        // Not swallowed and not reported as success: the save was attempted and the failure escaped.
        assertThat(thrown).isNotInstanceOf(ConflictException.class);
        verify(ticketRepository, times(1)).save(any(Ticket.class));
    }

    private static Ticket storedTicket(UUID id, TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setTitle("Login fails on SSO");
        ticket.setDescription("502 after redirect");
        ticket.setStatus(status);
        ticket.setPriority(TicketPriority.HIGH);
        ticket.setAssignee("bob");
        ticket.setCreatedAt(NOW);
        ticket.setUpdatedAt(NOW);
        ticket.setVersion(0L);
        return ticket;
    }
}
