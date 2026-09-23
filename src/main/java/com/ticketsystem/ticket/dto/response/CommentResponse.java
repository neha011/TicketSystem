package com.ticketsystem.ticket.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A comment as returned inside a {@link TicketDetailResponse} or by the comment creation endpoint
 * (Requirements 3.1, 5.2).
 *
 * <p>{@code author} is derived server-side from the authenticated principal, never echoed back from a
 * request body.
 */
@Schema(description = "A comment attached to a ticket.")
public record CommentResponse(
        @Schema(description = "Identifier of the comment.",
                example = "3f2a1c44-9b7e-4c1d-8a55-0d2f6b7c1e90")
        UUID id,

        @Schema(description = "Identifier of the support staff member who wrote the comment, taken "
                + "from the authenticated principal.", example = "a.patel")
        String author,

        @Schema(description = "Comment text, stored trimmed.",
                example = "Escalated to the platform team.")
        String content,

        @Schema(description = "When the comment was created, ISO-8601 UTC. Comments are ordered "
                + "oldest to newest by (createdAt, id).", example = "2026-09-05T10:15:30Z")
        Instant createdAt) {

    public CommentResponse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
