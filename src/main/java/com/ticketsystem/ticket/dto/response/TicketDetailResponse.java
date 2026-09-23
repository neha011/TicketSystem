package com.ticketsystem.ticket.dto.response;

import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The full representation of a single ticket, returned by create, detail, update, and status
 * transition (Requirements 1.6, 3.1, 5.2, 8.2).
 *
 * <p>This is the summary shape widened with {@code description} and {@code comments}. Records cannot
 * extend a record, so the shared fields are repeated here rather than inherited; {@link #summary()}
 * exists so the two shapes cannot drift when a caller needs the narrower one.
 *
 * <p>{@code comments} is always present, ordered oldest to newest by {@code (createdAt, id)}, and empty
 * rather than null when the ticket has no comments — the UI keys its "no comments" indication off an
 * empty array (Requirements 3.6, 3.7).
 */
@Schema(description = "Full ticket representation including description and comments.")
public record TicketDetailResponse(
        @Schema(description = "Identifier of the ticket.",
                example = "9f1c3b2a-5d4e-4f6a-8b7c-1e2d3f4a5b6c")
        UUID id,

        @Schema(description = "Ticket title, stored trimmed; 1 to 200 characters after trimming.",
                example = "Login fails on SSO")
        String title,

        @Schema(description = "Ticket description, at most 5000 characters, or null when none was "
                + "supplied.", example = "Users see a 502 after the identity provider redirect.",
                nullable = true)
        String description,

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
        long version,

        @Schema(description = "Comments on the ticket, ordered oldest to newest. Empty when the "
                + "ticket has no comments.")
        List<CommentResponse> comments) {

    public TicketDetailResponse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        // A ticket with no comments serializes as [] so the client never has to null-check the array.
        comments = (comments == null) ? List.of() : List.copyOf(comments);
    }

    /** @return the same ticket in the narrower list representation, with description and comments dropped. */
    public TicketSummaryResponse summary() {
        return new TicketSummaryResponse(
                id, title, status, priority, assignee, createdAt, updatedAt, version);
    }
}
