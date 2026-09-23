package com.ticketsystem.ticket.exception;

import com.ticketsystem.ticket.dto.response.FieldError;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/**
 * Base type for domain failures that map onto a specific HTTP status.
 *
 * <p>Each subclass declares its own status and carries a client-safe message, so the mapping lives
 * with the exception instead of an {@code if}-chain in the advice, and every message placed in a
 * response body has already been vetted as safe to expose (Requirements 10.2, 11.8).
 *
 * <p>Unchecked by design: service signatures stay free of checked-exception noise.
 */
public abstract class ApiException extends RuntimeException {

    private final transient List<FieldError> fieldErrors;

    protected ApiException(String clientMessage) {
        this(clientMessage, List.of(), null);
    }

    protected ApiException(String clientMessage, List<FieldError> fieldErrors) {
        this(clientMessage, fieldErrors, null);
    }

    protected ApiException(String clientMessage, Throwable cause) {
        this(clientMessage, List.of(), cause);
    }

    protected ApiException(String clientMessage, List<FieldError> fieldErrors, Throwable cause) {
        super(Objects.requireNonNull(clientMessage, "clientMessage"), cause);
        this.fieldErrors = List.copyOf(Objects.requireNonNullElse(fieldErrors, List.of()));
    }

    /** The HTTP status this failure maps to. */
    public abstract HttpStatus status();

    /**
     * Message safe to return to the caller. Distinct from {@link #getMessage()} only by intent: this
     * accessor is what the advice reads, which keeps the "never leak internals" rule explicit.
     */
    public String clientMessage() {
        return getMessage();
    }

    /** Field-level detail, empty when the failure is not attributable to specific fields. */
    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }
}
