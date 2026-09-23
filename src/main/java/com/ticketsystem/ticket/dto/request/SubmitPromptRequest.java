package com.ticketsystem.ticket.dto.request;

import com.ticketsystem.ticket.validation.TrimmedSize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/v1/prompt-cache} (Requirements 1.5, 1.6, 8.1, 8.2, 8.3).
 *
 * <p>{@code prompt} is mandatory. {@code @NotNull} and {@link TrimmedSize} are paired so a missing
 * prompt and a whitespace-only one get distinct client-facing reasons: {@code @NotNull} covers
 * absence, while {@code @TrimmedSize(min = 1, ...)} rejects a prompt that is empty after normalization
 * (Requirement 1.5) and enforces the 100000-character cap with the upper boundary inclusive
 * (Requirement 1.6). The trimmed-length rule is measured against the <em>raw</em> submitted value; no
 * trimming or sanitizing deserializer runs ahead of validation.
 *
 * <p>{@code model} and {@code response} are optional. {@code model} is a response-affecting parameter
 * that participates in cache-key derivation; {@code response} is an optional cached response to store
 * alongside the entry. Both may be {@code null} when the client does not supply them.
 */
@Schema(description = "Request body for submitting a prompt to the cache. Only 'prompt' is required; "
        + "'model' and 'response' are optional.")
public record SubmitPromptRequest(

        @Schema(description = "Prompt text. Required, and must be 1 to 100000 characters once leading "
                + "and trailing whitespace is removed, so a whitespace-only prompt is rejected.",
                example = "Summarize this ticket",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "must be provided")
        @TrimmedSize(min = 1, max = 100000)
        String prompt,

        @Schema(description = "Optional response-affecting parameter (for example the AI model "
                + "identifier). Prompts with different models derive different cache keys.",
                example = "gpt-4",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String model,

        @Schema(description = "Optional cached response to store with the entry. When supplied for a "
                + "prompt whose key matches no existing entry, the submission is rejected.",
                example = "The ticket describes a login failure affecting mobile users.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String response) {
}
