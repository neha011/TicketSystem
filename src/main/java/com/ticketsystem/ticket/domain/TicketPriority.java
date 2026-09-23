package com.ticketsystem.ticket.domain;

/**
 * The urgency level of a ticket. Only these four values are accepted; any other value submitted by a
 * client is a validation failure rather than a coerced default.
 */
public enum TicketPriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
