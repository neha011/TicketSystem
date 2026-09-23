package com.ticketsystem.ticket.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

/**
 * One field-level validation failure inside an {@link ErrorResponse}.
 *
 * <p>Every failing field of a rejected request contributes exactly one entry, so a client can
 * annotate each input independently instead of guessing from a single summary message
 * (Requirements 4.10, 10.2).
 */
@Schema(description = "A single field-level validation failure.")
public record FieldError(
        @Schema(description = "Name of the request field that failed validation.", example = "title")
        String field,

        @Schema(
                description = "Client-safe explanation of why the field failed. Never contains "
                        + "internal detail such as exception text or SQL.",
                example = "must be 1 to 200 characters after trimming")
        String reason) {

    public FieldError {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(reason, "reason");
    }
}
