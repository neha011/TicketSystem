package com.ticketsystem.ticket.exception;

import org.springframework.http.HttpStatus;

/**
 * The prompt cache could not complete an operation: a key-derivation collision, a write-lock
 * acquisition timeout, or a failure persisting the Cache_File (Requirements 2.6, 6.2, 7.3).
 *
 * <p>Maps to 500 Internal Server Error. Always carries the same constant, client-safe message so no
 * internal detail leaks into the response body; the full cause is logged server-side at ERROR by the
 * originating component.
 */
public class CacheUnavailableException extends ApiException {

    /** Constant, client-safe message; never varies so no internal detail can leak. */
    public static final String CLIENT_MESSAGE = "The prompt cache is temporarily unavailable.";

    public CacheUnavailableException() {
        super(CLIENT_MESSAGE);
    }

    /**
     * Preserves the underlying cause for server-side logging while still exposing only the constant
     * client-safe message to callers.
     */
    public CacheUnavailableException(Throwable cause) {
        super(CLIENT_MESSAGE, cause);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
