package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.exception.ForbiddenException;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Baseline authorization policy: any identified actor may view, modify, and comment on any ticket;
 * an unidentified actor may do nothing.
 *
 * <p>The requirements define no role model or per-ticket ownership rules, so this implementation
 * deliberately encodes the one rule they do state — a caller without a valid identity is refused
 * (Requirement 3.5) — and nothing more. A richer policy (assignee-only modification, team
 * visibility) replaces this bean without touching a single call site, which is the reason callers
 * depend on {@link TicketAuthorizationService} rather than on this class.
 *
 * <p>The check fails closed. A null or blank actor means the service was reached without an
 * authenticated principal, which is a wiring mistake rather than a routine denial; answering 403 is
 * safer than assuming the filter chain already vetted the call.
 */
@Service
public class DefaultTicketAuthorizationService implements TicketAuthorizationService {

    @Override
    public void requireCanView(String actor, Ticket ticket) {
        requireIdentifiedActor(actor, ticket);
    }

    @Override
    public void requireCanModify(String actor, Ticket ticket) {
        requireIdentifiedActor(actor, ticket);
    }

    @Override
    public void requireCanComment(String actor, Ticket ticket) {
        requireIdentifiedActor(actor, ticket);
    }

    @Override
    public void requireCanList(String actor) {
        requireIdentifiedActor(actor);
    }

    /**
     * A ticket must always be supplied: the contract is that authorization runs against a loaded
     * entity, so a null here means the caller skipped the load and is a programming error, not a 403.
     */
    private void requireIdentifiedActor(String actor, Ticket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        requireIdentifiedActor(actor);
    }

    /** The ticket-free form, used by the list check, which runs before any ticket is loaded. */
    private void requireIdentifiedActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new ForbiddenException();
        }
    }
}
