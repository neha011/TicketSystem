package com.ticketsystem.ticket.exception;

import org.springframework.http.HttpStatus;

/**
 * A state conflict: an illegal or same-status transition, a transition out of a terminal state, or a
 * stale version supplied on a write (Requirements 4.6, 8.3-8.6, 8.9).
 *
 * <p>Maps to 409 Conflict. Transition rejections name both the current and the requested status in
 * the message so the UI can render it directly rather than inventing its own text (Requirement 8.7).
 */
public class ConflictException extends ApiException {

    public ConflictException(String clientMessage) {
        super(clientMessage);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
