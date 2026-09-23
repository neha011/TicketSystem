package com.ticketsystem.ticket.exception;

import com.ticketsystem.ticket.dto.response.FieldError;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A syntactically valid value that violates a business rule, such as an assignee that names no
 * existing user (Requirement 4.5).
 *
 * <p>Maps to 422 Unprocessable Entity. Kept distinct from {@link ValidationException} (400) because
 * the request is well-formed; only its meaning is rejected.
 */
public class UnprocessableEntityException extends ApiException {

    public UnprocessableEntityException(String clientMessage) {
        super(clientMessage);
    }

    public UnprocessableEntityException(String clientMessage, List<FieldError> fieldErrors) {
        super(clientMessage, fieldErrors);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
