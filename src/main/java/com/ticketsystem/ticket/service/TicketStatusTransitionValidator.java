package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.exception.ConflictException;
import java.util.Objects;
import java.util.Set;

/**
 * Decides whether a ticket status transition is permitted (Requirements 8.1, 8.3, 8.4, 8.6, 8.7).
 *
 * <p>Pure by design: no Spring annotations, no constructor arguments, no state, and no I/O. The most
 * safety-critical rule in the system is therefore a plain function that can be exhaustively
 * property-tested over the whole {@code TicketStatus × TicketStatus} space without a Spring context,
 * a database, or mocks.
 *
 * <p>The decision delegates entirely to the single authoritative table on {@link TicketStatus}, so
 * the rule cannot drift from the type. Same-status transitions (Requirement 8.4) and transitions out
 * of terminal states (Requirement 8.6) need no dedicated branch here: no state appears in its own
 * row, and terminal states have empty rows.
 */
public final class TicketStatusTransitionValidator {

    /**
     * @return true if and only if {@code (from, to)} appears in the allowed-transition table.
     */
    public boolean isPermitted(TicketStatus from, TicketStatus to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return from.allowedTargets().contains(to);
    }

    /**
     * @return the permitted target states for {@code from}; empty for terminal states. The returned
     *     set is unmodifiable.
     */
    public Set<TicketStatus> permittedTargets(TicketStatus from) {
        Objects.requireNonNull(from, "from");
        return from.allowedTargets();
    }

    /**
     * Enforces the transition table, throwing rather than returning a flag so callers cannot forget
     * to check the result.
     *
     * @throws ConflictException (409) if the transition is not permitted. The message names both the
     *     current and the requested status so the UI can render it directly instead of inventing its
     *     own text (Requirement 8.7).
     */
    public void requirePermitted(TicketStatus from, TicketStatus to) {
        if (!isPermitted(from, to)) {
            throw new ConflictException(
                    "Cannot transition ticket from " + from.name() + " to " + to.name());
        }
    }
}
