package com.ticketsystem.ticket.dto.request;

import com.ticketsystem.ticket.domain.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /api/v1/tickets/{id}/status} (Requirements 8.2, 8.8, 8.9).
 *
 * <p>{@code status} is typed as the enum rather than as a {@code String}, which is what keeps the two
 * failure modes in Requirement 8.8 apart: a value that is not a defined {@code TicketStatus} name never
 * reaches the state machine at all — Jackson rejects it while parsing and the result is a 400. Only a
 * well-formed status that the transition table forbids produces a 409. An undefined value can therefore
 * never be misreported as a conflict.
 *
 * <p>{@code version} is the optimistic-locking value the client last read. It is boxed and
 * {@code @NotNull} so an omitted version is a 400 rather than silently defaulting to {@code 0}, which a
 * primitive {@code long} would do — and a defaulted {@code 0} would let a stale client win against a
 * freshly created ticket (Requirement 8.9).
 */
@Schema(description = "Request body for a ticket status transition.")
public record StatusTransitionRequest(

        @Schema(description = "Requested target status. Must be exactly one of the defined values; "
                + "an undefined name is a 400, while a defined name the transition table forbids is "
                + "a 409.", example = "IN_PROGRESS", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "must be one of OPEN, IN_PROGRESS, RESOLVED, CLOSED, CANCELLED")
        TicketStatus status,

        @Schema(description = "Version of the ticket the client last read. Required; a value that "
                + "does not match the stored version is a 409 conflict.", example = "3",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "must be provided")
        Long version) {
}
