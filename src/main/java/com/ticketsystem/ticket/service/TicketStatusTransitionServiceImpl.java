package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.mapper.TicketMapper;
import com.ticketsystem.ticket.dto.response.TicketDetailResponse;
import com.ticketsystem.ticket.exception.ConflictException;
import com.ticketsystem.ticket.exception.NotFoundException;
import com.ticketsystem.ticket.repository.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link TicketStatusTransitionService}: owns the transaction boundary around a status
 * change, the version check on the write, and the ordering that keeps a rejected transition from
 * touching the ticket (Requirements 8.2-8.6, 8.9).
 *
 * <p>The order of the steps is the substance of this class, not incidental. Load, authorize, compare
 * the supplied version, then ask {@link TicketStatusTransitionValidator} whether the transition is
 * permitted — and only then set the status. Every rejection therefore happens while the loaded ticket
 * is still untouched, so a failed transition leaves the record exactly as it was without relying on a
 * rollback to undo a half-applied change. The validator is consulted <em>before</em> the entity is
 * mutated, so a rejected transition never dirties it (Requirements 8.3-8.6).
 *
 * <p>The version comparison is explicit rather than left entirely to JPA's {@code @Version} check on
 * flush. Hibernate only notices a conflict when a <em>concurrent</em> write has bumped the version
 * between this transaction's read and its flush; a client acting on a status it read some time ago
 * would otherwise have its stale request applied silently. Comparing what the client claims to have
 * seen against what is stored catches that case (Requirement 8.9). The {@code @Version} check still
 * runs underneath on flush and surfaces the narrower interleaving as an
 * {@code ObjectOptimisticLockingFailureException}, which the exception handler maps to 409.
 */
@Service
public class TicketStatusTransitionServiceImpl implements TicketStatusTransitionService {

    private static final Logger log = LoggerFactory.getLogger(TicketStatusTransitionServiceImpl.class);

    private final TicketRepository ticketRepository;
    private final TicketMapper ticketMapper;
    private final TicketAuthorizationService authorizationService;
    private final TicketStatusTransitionValidator transitionValidator;
    private final Clock clock;

    public TicketStatusTransitionServiceImpl(
            TicketRepository ticketRepository,
            TicketMapper ticketMapper,
            TicketAuthorizationService authorizationService,
            TicketStatusTransitionValidator transitionValidator,
            Clock clock) {
        this.ticketRepository = Objects.requireNonNull(ticketRepository, "ticketRepository");
        this.ticketMapper = Objects.requireNonNull(ticketMapper, "ticketMapper");
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        this.transitionValidator = Objects.requireNonNull(transitionValidator, "transitionValidator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code targetStatus} is required as an enum, so this method is never reached with an undefined
     * status — that failure is a 400 at the parse boundary. A well-formed status the table forbids is a
     * 409 raised by {@link TicketStatusTransitionValidator#requirePermitted}, whose message names both
     * the current and requested status (Requirement 8.7).
     */
    @Override
    @Transactional
    public TicketDetailResponse transitionStatus(
            UUID id, TicketStatus targetStatus, long expectedVersion, String actor) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(targetStatus, "targetStatus");

        Ticket ticket = requireTicket(id);
        authorizationService.requireCanModify(actor, ticket);
        requireCurrentVersion(ticket, expectedVersion);
        // Consulted before any mutation: a rejected transition throws here, leaving status, updatedAt,
        // and version exactly as loaded and issuing no save (Requirements 8.3-8.6).
        transitionValidator.requirePermitted(ticket.getStatus(), targetStatus);

        // Past this line the transition is permitted, so the mutations below cannot be stranded.
        TicketStatus previousStatus = ticket.getStatus();
        ticket.setStatus(targetStatus);
        ticket.setUpdatedAt(nextUpdatedAt(ticket.getUpdatedAt()));

        Ticket saved = ticketRepository.save(ticket);
        log.info(
                "Ticket {} transitioned from {} to {} by {}",
                saved.getId(),
                previousStatus,
                saved.getStatus(),
                actor);
        return ticketMapper.toDetail(saved);
    }

    /**
     * Rejects a request whose expected version does not match the stored one (Requirement 8.9).
     *
     * <p>The message names both versions because that is what tells the caller their copy is stale, and
     * both values are ones they either sent or are entitled to read back.
     */
    private static void requireCurrentVersion(Ticket ticket, long expectedVersion) {
        if (expectedVersion != ticket.getVersion()) {
            throw new ConflictException(
                    "Ticket "
                            + ticket.getId()
                            + " was modified by another request: expected version "
                            + expectedVersion
                            + " but the stored version is "
                            + ticket.getVersion());
        }
    }

    /**
     * The instant to stamp the transition with: the clock's reading, or the smallest value strictly
     * after {@code previousUpdatedAt} when the clock has not visibly moved since the last write.
     *
     * <p>A plain {@code clock.instant()} would let a transition applied within the same clock tick as
     * the previous write record an identical {@code updatedAt}, making the change indistinguishable
     * from no change — and "is this ticket newer than the copy I hold?" is exactly what this field is
     * read for. The nudge is one microsecond, not one nanosecond, because PostgreSQL's
     * {@code timestamptz} resolution is microseconds and a nanosecond bump would be truncated away on
     * store.
     */
    private Instant nextUpdatedAt(Instant previousUpdatedAt) {
        Instant now = clock.instant();
        if (previousUpdatedAt == null || now.isAfter(previousUpdatedAt)) {
            return now;
        }
        return previousUpdatedAt.plus(1, ChronoUnit.MICROS);
    }

    /**
     * Loads a ticket with its comments or fails with a 404 (Requirement 8.9 path; also 3.2).
     *
     * <p>The comments are fetched here so the {@link TicketMapper#toDetail} response can carry them
     * without a second query after the transaction that loaded the ticket has closed
     * ({@code spring.jpa.open-in-view} is false).
     */
    private Ticket requireTicket(UUID id) {
        return ticketRepository
                .findWithCommentsById(id)
                .orElseThrow(() -> new NotFoundException("Ticket " + id + " not found"));
    }
}
