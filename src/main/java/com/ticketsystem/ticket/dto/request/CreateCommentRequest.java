package com.ticketsystem.ticket.dto.request;

import com.ticketsystem.ticket.validation.TrimmedSize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/v1/tickets/{id}/comments} (Requirements 5.1, 5.3, 5.4, 5.5, 5.8).
 *
 * <p><b>{@code content} is the only field.</b> There is deliberately no {@code author}: the author is
 * derived from the authenticated principal in the service layer, so a client cannot attribute a comment to
 * somebody else. Leaving the field out of the DTO entirely, rather than accepting and ignoring it, means
 * there is no code path that could start trusting it later.
 *
 * <p>{@code @NotNull} and {@link TrimmedSize} are paired so a missing comment and a whitespace-only one get
 * distinct client-facing reasons. The trimmed-length rule is evaluated against the <em>raw</em> submitted
 * value — no trimming or sanitizing deserializer runs ahead of validation, which is what Requirement 5.8
 * requires.
 */
@Schema(description = "Request body for adding a comment to a ticket. The author is taken from the "
        + "authenticated principal and is never accepted from the body.")
public record CreateCommentRequest(

        @Schema(description = "Comment text. Required, and must be 1 to 5000 characters once leading and "
                + "trailing whitespace is removed, so a whitespace-only comment is rejected.",
                example = "Escalated to the platform team.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "must be provided")
        @TrimmedSize(min = 1, max = 5000)
        String content) {
}
