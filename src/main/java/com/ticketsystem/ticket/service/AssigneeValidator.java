package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.exception.UnprocessableEntityException;

/**
 * Resolves a submitted assignee identifier against the {@link UserDirectory} (Requirement 4.5).
 *
 * <p>Separate from {@code @Size(max=100)} on the request DTOs because the two answer different
 * questions: the DTO constraint judges the shape of the value (400 when malformed), this validator
 * judges its meaning (422 when well-formed but naming no one).
 */
public interface AssigneeValidator {

    /**
     * Enforces that an assignee, when present, names an existing user.
     *
     * <p>Absence is not a failure: a null assignee means unassigned on create and "clear the
     * assignee" on update, both of which are legitimate.
     *
     * @param assignee candidate identifier, or null for unassigned
     * @throws UnprocessableEntityException (422) with a {@code fieldErrors} entry for {@code
     *     assignee} when the value names no existing user
     */
    void requireExists(String assignee);
}
