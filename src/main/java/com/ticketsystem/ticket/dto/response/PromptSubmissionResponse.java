package com.ticketsystem.ticket.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

/**
 * Body returned by {@code POST /api/v1/prompt-cache} (Requirements 8.1, 8.2).
 *
 * <p>{@code cacheHit} distinguishes the two success outcomes: {@code true} with a {@code 200 OK} when
 * the derived key matched an existing non-expired entry (Requirement 8.1), {@code false} with a
 * {@code 201 Created} when the submission created a new entry (Requirement 8.2). {@code cachedResponse}
 * is present only when a response is stored for the entry; it is {@code null} otherwise, giving the
 * caller an explicit no-response indication.
 */
@Schema(description = "Result of submitting a prompt to the cache.")
public record PromptSubmissionResponse(

        @Schema(description = "The fixed-length key derived from the normalized prompt and its "
                + "response-affecting parameters.",
                example = "a1b2c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff00")
        String cacheKey,

        @Schema(description = "True when the prompt matched an existing non-expired entry (a cache "
                + "hit); false when a new entry was created.", example = "false")
        boolean cacheHit,

        @Schema(description = "The stored cached response, when one is present; null when no response "
                + "is stored for the entry.",
                example = "The ticket describes a login failure affecting mobile users.")
        String cachedResponse) {

    public PromptSubmissionResponse {
        Objects.requireNonNull(cacheKey, "cacheKey");
    }
}
