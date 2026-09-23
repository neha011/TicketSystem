package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.exception.ForbiddenException;

/**
 * Authorization gate for ticket operations (Requirement 3.5).
 *
 * <p>Invoked from {@code TicketService}, {@code TicketStatusTransitionService}, and {@code
 * CommentService} rather than from controllers. The controller is only one entry point; putting the
 * check in the service means a scheduled job, a message consumer, or a future non-HTTP entry point
 * calling the same method inherits the same enforcement instead of having to remember it.
 *
 * <p>Callers invoke the check <em>after</em> loading the ticket — a decision may depend on the
 * ticket's assignee or state — but <em>before</em> any mutation, so a denied request cannot have
 * written anything.
 *
 * <p>Every method is void and throws on denial rather than returning a boolean, so a caller cannot
 * silently ignore the verdict.
 */
public interface TicketAuthorizationService {

    /**
     * @param actor identifier of the authenticated caller, as derived from the principal
     * @param ticket the already-loaded ticket the caller wants to read
     * @throws ForbiddenException (403) if the actor may not view this ticket
     */
    void requireCanView(String actor, Ticket ticket);

    /**
     * The one check with no ticket argument: the list endpoint decides whether the caller may query at
     * all <em>before</em> a query runs.
     *
     * <p>Checking per returned ticket instead would make the verdict depend on how many rows the page
     * happened to contain — an empty page has nothing to authorize, so an unidentified caller would
     * still learn the total number of matching tickets. Gating the query itself closes that.
     *
     * @param actor identifier of the authenticated caller
     * @throws ForbiddenException (403) if the actor may not list tickets
     */
    void requireCanList(String actor);

    /**
     * @throws ForbiddenException (403) if the actor may not change this ticket's fields or status
     */
    void requireCanModify(String actor, Ticket ticket);

    /**
     * @throws ForbiddenException (403) if the actor may not add a comment to this ticket
     */
    void requireCanComment(String actor, Ticket ticket);
}
