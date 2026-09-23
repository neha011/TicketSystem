package com.ticketsystem.ticket.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lifecycle state of a ticket, together with the single authoritative table of permitted
 * status transitions.
 *
 * <p>The table lives on the enum itself so it cannot drift from the type: adding a state without
 * giving it a row fails fast at class-initialization time rather than silently behaving as if the
 * state had no outgoing transitions.
 *
 * <p>No state appears in its own row of the table. Rejecting a transition whose target equals the
 * current status therefore falls out of the table itself and needs no separate branch anywhere in
 * the system.
 */
public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CLOSED,
    CANCELLED;

    /**
     * Permitted target states keyed by source state. CLOSED and CANCELLED are terminal and map to
     * the empty set. Every set is unmodifiable so callers cannot mutate the shared table.
     */
    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED;

    static {
        Map<TicketStatus, Set<TicketStatus>> allowed = new EnumMap<>(TicketStatus.class);
        allowed.put(OPEN, unmodifiable(EnumSet.of(IN_PROGRESS, CANCELLED)));
        allowed.put(IN_PROGRESS, unmodifiable(EnumSet.of(RESOLVED, CANCELLED)));
        allowed.put(RESOLVED, unmodifiable(EnumSet.of(CLOSED)));
        allowed.put(CLOSED, unmodifiable(EnumSet.noneOf(TicketStatus.class)));
        allowed.put(CANCELLED, unmodifiable(EnumSet.noneOf(TicketStatus.class)));
        ALLOWED = Collections.unmodifiableMap(allowed);
    }

    private static Set<TicketStatus> unmodifiable(Set<TicketStatus> targets) {
        return Collections.unmodifiableSet(targets);
    }

    /**
     * @return the states this status may transition to; empty for terminal states. The returned set
     *     is unmodifiable.
     */
    public Set<TicketStatus> allowedTargets() {
        return ALLOWED.get(this);
    }

    /**
     * @return true when this status has no permitted outgoing transitions (CLOSED, CANCELLED).
     */
    public boolean isTerminal() {
        return allowedTargets().isEmpty();
    }
}
