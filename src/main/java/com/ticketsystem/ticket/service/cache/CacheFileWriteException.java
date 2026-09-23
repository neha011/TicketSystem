package com.ticketsystem.ticket.service.cache;

/**
 * Signals that persisting the Cache_File failed (Requirement 7.3).
 *
 * <p>Thrown by {@link CacheFileWriter#write} after the temporary file has been cleaned up, so no
 * partial file remains. The store converts this into a rolled-back in-memory mutation and a
 * client-safe {@code CacheUnavailableException} (500); this exception itself never reaches the HTTP
 * boundary, so it carries the internal cause for server-side logging only.
 */
public class CacheFileWriteException extends RuntimeException {

    public CacheFileWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
