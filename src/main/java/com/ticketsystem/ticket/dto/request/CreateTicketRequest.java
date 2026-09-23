package com.ticketsystem.ticket.dto.request;

import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.validation.TrimmedSize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/tickets} (Requirements 1.1, 1.3, 1.4, 1.5, 10.1).
 *
 * <p>Neither {@code status} nor {@code version} is accepted here: a new ticket is always created
 * {@code OPEN} by the service (Requirement 1.1) and its version is owned by the persistence layer, so
 * there is nothing for a client to supply and no way for one to seed a ticket mid-lifecycle.
 *
 * <p>{@code title} carries both {@code @NotNull} and {@link TrimmedSize} on purpose. {@code @TrimmedSize}
 * lets null pass, following the same convention as {@code @Size}, so presence needs its own constraint —
 * and keeping them separate is what gives a missing title and a blank title distinct client-facing
 * reasons in {@code fieldErrors} (Requirements 1.3, 10.2).
 *
 * <p>Every field is validated independently, so a request failing several rules reports all of them
 * rather than stopping at the first (Requirement 10.2).
 */
@Schema(description = "Request body for creating a ticket. Status is always OPEN on creation and is "
        + "not accepted from the client.")
public record CreateTicketRequest(

        @Schema(description = "Ticket title. Required, and must be 1 to 200 characters once leading "
                + "and trailing whitespace is removed, so a whitespace-only title is rejected.",
                example = "Login fails on SSO", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "must be provided")
        @TrimmedSize(min = 1, max = 200)
        String title,

        @Schema(description = "Optional description, at most 5000 characters. Unlike the title, this "
                + "may be blank — a caller who has nothing to add is not forced to invent text.",
                example = "Users see a 502 after the identity provider redirect.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 5000)
        String description,

        @Schema(description = "Urgency level. Required; must be exactly one of the defined values, as "
                + "an unrecognized name is rejected at parse time rather than coerced to null.",
                example = "HIGH", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "must be one of LOW, MEDIUM, HIGH, CRITICAL")
        TicketPriority priority,

        @Schema(description = "Optional support staff member to assign the ticket to, at most 100 "
                + "characters. Omit or send null to leave the ticket unassigned. A syntactically "
                + "valid identifier that names no existing user is a 422, not a 400.",
                example = "a.patel", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @Size(max = 100)
        String assignee) {
}
