package com.ticketsystem.ticket.exception;

import com.ticketsystem.ticket.dto.response.FieldError;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A service-layer input invariant that is not expressible as a DTO constraint, for example a
 * keyword length that reaches the service without passing through query-param validation
 * (Requirements 10.1, 10.2).
 *
 * <p>Maps to 400 Bad Request. Field-level detail is optional so a caller can be told exactly which
 * field failed and why.
 */
public class ValidationException extends ApiException {

    public ValidationException(String clientMessage) {
        super(clientMessage);
    }

    public ValidationException(String clientMessage, List<FieldError> fieldErrors) {
        super(clientMessage, fieldErrors);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }
}
