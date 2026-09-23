package com.ticketsystem.ticket.service.cache;

/**
 * Signals that the Cache_File exists but could not be read or does not contain valid JSON
 * (Requirement 7.1).
 *
 * <p>Thrown by {@link CacheFileReader#read()} at startup. It is a load-time signal, not a client
 * error: the store catches it, logs at WARN, and starts with an empty in-memory cache rather than
 * aborting application startup, so this exception never reaches the HTTP boundary.
 */
public class CacheFileReadException extends RuntimeException {

    public CacheFileReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
