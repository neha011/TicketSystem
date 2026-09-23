package com.ticketsystem.ticket.exception;

import org.springframework.http.HttpStatus;

/**
 * A well-formed identifier that names no existing record (Requirements 3.2, 4.4, 5.6).
 *
 * <p>Maps to 404 Not Found.
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String clientMessage) {
        super(clientMessage);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
