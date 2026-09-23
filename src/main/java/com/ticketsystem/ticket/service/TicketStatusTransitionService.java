package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.response.TicketDetailResponse;
import com.ticketsystem.ticket.exception.ConflictException;
import com.ticketsystem.ticket.exception.ForbiddenException;
import com.ticketsystem.ticket.exception.NotFoundException;
import java.util.UUID;

/**
 * Moves a ticket through the status state machine (Requirements 8.2-8.6, 8.9).
 *
 * <p>Status changes live on their own service rather than on {@link TicketService#update} on purpose:
 * a status change is the one mutation gated by the transition table, and keeping it off the general
 * field-update path means that path cannot be used to bypass the state machine (Requirements 8.1-8.6).
 *
 * <p>The target is typed as {@link TicketStatus}, not {@code String}. An undefined status value is
 * therefore unrepresentable at this layer at all: an unknown name is rejected as a 400 while the
 * request is still being parsed, so only a well-formed status the table forbids can reach here, and
 * that is a 409 (Requirement 8.8). The two failure modes never collapse into one.
 *
 * <p>Authorization is enforced here rather than in the controller so every entry point — a scheduled
 * job or message consumer as much as an HTTP request — inherits the same check (Requirement 3.5).
 * Callers receive a DTO; the entity never leaves this layer.
 */
public interface TicketStatusTransitionService {

    /**
     * Applies a permitted status transition and advances {@code updatedAt} (Requirements 8.2, 8.9).
     *
     * <p>The checks run in a fixed order — load, authorize, compare the supplied version, consult the
     * transition table — and the entity is not touched until every one has passed, so a rejected
     * transition leaves the ticket exactly as it was (Requirements 8.3-8.6). The whole sequence runs in
     * one transaction, so even the save is undone if the optimistic-lock check fails on flush.
     *
     * @param id identifier of the ticket to transition; a malformed identifier is rejected at the API
     *     boundary and cannot reach here
     * @param targetStatus the requested status; typed as the enum so an undefined value cannot reach
     *     this layer
     * @param expectedVersion the optimistic-locking value the client last read
     * @param actor identifier of the authenticated caller
     * @return the transitioned ticket, carrying the new status and the bumped version the client must
     *     round-trip next time
     * @throws NotFoundException (404) if no ticket has this identifier
     * @throws ForbiddenException (403) if the caller may not modify this ticket (Requirement 3.5)
     * @throws ConflictException (409) if {@code expectedVersion} does not match the stored version, or
     *     if the transition is not permitted by the table — including a same-status request and a
     *     transition out of a terminal state (Requirements 8.3-8.6, 8.9). The message names both the
     *     current and the requested status (Requirement 8.7).
     */
    TicketDetailResponse transitionStatus(
            UUID id, TicketStatus targetStatus, long expectedVersion, String actor);
}
