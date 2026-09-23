package com.ticketsystem.ticket.service;

/**
 * Read-only lookup over the identities that may be named as a ticket assignee (Requirement 4.5).
 *
 * <p>Modelled as its own boundary so the question "does this user exist?" stays answerable without
 * the ticket service knowing where users live. Today the answer comes from configuration; when an
 * identity provider or a user table arrives, only the implementation changes.
 */
public interface UserDirectory {

    /**
     * @param userId candidate identifier, exactly as submitted
     * @return true if the directory contains a user with this identifier. Never throws for unknown,
     *     null, or malformed input — the caller decides what an absent user means.
     */
    boolean exists(String userId);
}
