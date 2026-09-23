package com.ticketsystem.ticket.dto.request;

import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.validation.TrimmedSize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/v1/tickets/{id}} (Requirements 4.1, 4.2, 4.3, 4.6).
 *
 * <p>Every updatable field is wrapped in {@link Patch} so an omitted field and an explicitly null one stay
 * distinguishable: omitting a field leaves the stored value untouched, while sending {@code null} clears it
 * where the field is nullable (Requirement 4.2). Constraints are written against the wrapped value via
 * {@code PatchValueExtractor}, so a field the client never mentioned is not validated at all and cannot
 * fail.
 *
 * <p>{@code title} keeps {@code @NotNull} alongside its trimmed-length rule: omitting the title is fine,
 * but asking to set it to {@code null} is not, since a ticket must always have one (Requirement 1.3).
 * {@code assignee} carries no {@code @NotNull}, because clearing an assignment is a legitimate edit — the
 * unassigned state is exactly what the "Unassigned" placeholder renders.
 *
 * <p><b>{@code status} is absent from this DTO by design.</b> Status changes go through
 * {@code PATCH /api/v1/tickets/{id}/status} and {@code StatusTransitionRequest}, so there is no field here
 * that could route a status change around the transition table (Requirements 8.1–8.6).
 *
 * <p>{@code version} is required and boxed, so an omitted version is a 400 rather than defaulting to
 * {@code 0} the way a primitive would — a defaulted {@code 0} would let a stale client overwrite a
 * concurrently modified ticket instead of receiving the 409 Requirement 4.6 calls for.
 */
@Schema(description = "Request body for a partial ticket update. Any subset of title, description, "
        + "priority, and assignee may be supplied; version is always required. An omitted field is left "
        + "unchanged, while an explicit null clears a nullable field. Status is not updatable here.")
public record UpdateTicketRequest(

        @Schema(description = "New title, 1 to 200 characters after trimming. Omit to leave unchanged; "
                + "null is rejected, as a ticket must always have a title.",
                example = "Login fails on SSO for EU tenants",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @NotNull(message = "must not be null")
        @TrimmedSize(min = 1, max = 200)
        Patch<String> title,

        @Schema(description = "New description, at most 5000 characters. Omit to leave unchanged; send "
                + "null to clear it.", example = "Users see a 502 after the identity provider redirect.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @Size(max = 5000)
        Patch<String> description,

        @Schema(description = "New urgency level. Omit to leave unchanged; null is rejected, as a ticket "
                + "always carries a priority.", example = "CRITICAL",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @NotNull(message = "must be one of LOW, MEDIUM, HIGH, CRITICAL")
        Patch<TicketPriority> priority,

        @Schema(description = "New assignee, at most 100 characters. Omit to leave the current assignee "
                + "unchanged; send null to unassign the ticket. An identifier that names no existing user "
                + "is a 422, not a 400.", example = "a.patel",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @Size(max = 100)
        Patch<String> assignee,

        @Schema(description = "Version of the ticket the client last read. Required; a value that does "
                + "not match the stored version is a 409 conflict.", example = "3",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "must be provided")
        Long version) {

    /**
     * Normalizes the {@code Patch} components so a field Jackson left null — which happens when a record
     * is built through a path that bypasses the deserializer's absent handling, and whenever one of these
     * DTOs is constructed directly in a test — reads as absent rather than throwing downstream.
     */
    public UpdateTicketRequest {
        title = (title == null) ? Patch.absent() : title;
        description = (description == null) ? Patch.absent() : description;
        priority = (priority == null) ? Patch.absent() : priority;
        assignee = (assignee == null) ? Patch.absent() : assignee;
    }

    /** @return true when the body supplied no updatable field at all, leaving nothing to apply. */
    public boolean hasNoFields() {
        return !title.isPresent()
                && !description.isPresent()
                && !priority.isPresent()
                && !assignee.isPresent();
    }
}
