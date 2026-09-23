package com.ticketsystem.ticket.exception;

import org.springframework.http.HttpStatus;

/**
 * An authenticated caller that is not permitted to act on the requested ticket (Requirement 3.5).
 *
 * <p>Maps to 403 Forbidden. Deliberately distinct from {@link NotFoundException}: ticket ids are
 * opaque UUIDs, so answering 403 leaks nothing exploitable, and collapsing the two would make the
 * 404-versus-403 distinction the UI renders (Requirements 11.4, 11.7) impossible to reproduce.
 *
 * <p>The message is a fixed, resource-free sentence. Requirement 3.5 forbids placing any ticket
 * data, comment, or other ticket-related field in a 403 body, so nothing about the denied ticket is
 * ever interpolated into it.
 */
public class ForbiddenException extends ApiException {

    /** Single client-facing message, carrying no detail about the ticket that was denied. */
    public static final String MESSAGE = "You are not permitted to perform this action on this ticket";

    public ForbiddenException() {
        super(MESSAGE);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.FORBIDDEN;
    }
}
