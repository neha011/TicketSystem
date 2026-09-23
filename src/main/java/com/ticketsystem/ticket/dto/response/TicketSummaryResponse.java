package com.ticketsystem.ticket.dto.response;

import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A ticket as it appears in the paginated list (Requirements 1.6, 2.6).
 *
 * <p>Carries everything the list view renders plus the optimistic-lock {@code version}, so a client
 * can move straight from a list row to an update or a status transition without re-fetching the
 * detail representation.
 *
 * <p>{@code assignee} is serialized as an explicit {@code null} rather than omitted when the ticket is
 * unassigned: the UI distinguishes "unassigned" from "field missing" and renders the "Unassigned"
 * placeholder from it (Requirement 2.7).
 */
@Schema(description = "Ticket summary as returned in the paginated ticket list.")
public record TicketSummaryResponse(
        @Schema(description = "Identifier of the ticket.",
                example = "9f1c3b2a-5d4e-4f6a-8b7c-1e2d3f4a5b6c")
        UUID id,

        @Schema(description = "Ticket title, stored trimmed; 1 to 200 characters after trimming.",
                example = "Login fails on SSO")
        String title,

        @Schema(description = "Current lifecycle state of the ticket.", example = "OPEN")
        TicketStatus status,

        @Schema(description = "Urgency level of the ticket.", example = "HIGH")
        TicketPriority priority,

        @Schema(description = "Support staff member responsible for the ticket, or null when the "
                + "ticket is unassigned.", example = "a.patel", nullable = true)
        String assignee,

        @Schema(description = "When the ticket was created, ISO-8601 UTC.",
                example = "2026-09-05T10:15:30Z")
        Instant createdAt,

        @Schema(description = "When the ticket was last modified, ISO-8601 UTC. Advances on every "
                + "successful field update or status transition.", example = "2026-09-05T10:15:30Z")
        Instant updatedAt,

        @Schema(description = "Optimistic-locking version. Clients must echo the value they read "
                + "back on update and status-transition requests; a mismatch is a 409.",
                example = "0")
        long version) {

    public TicketSummaryResponse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
