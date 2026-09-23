package com.ticketsystem.ticket.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/**
 * The single error shape returned by every non-2xx response, including the ones written by the
 * security filter chain before a controller is reached (Requirements 3.4, 3.5, 10.2, 11.8).
 *
 * <p>{@code message} is always a client-safe summary. Exception text, SQL fragments, and stack
 * traces are logged server-side and never placed here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Standard error body used by every failing endpoint.")
public record ErrorResponse(
        @Schema(description = "When the failure was produced, ISO-8601 UTC.",
                example = "2026-09-05T10:15:30Z")
        Instant timestamp,

        @Schema(description = "HTTP status code of the response.", example = "400")
        int status,

        @Schema(description = "HTTP reason phrase for the status code.", example = "Bad Request")
        String error,

        @Schema(description = "Client-safe summary of the failure.",
                example = "Validation failed for 2 field(s)")
        String message,

        @Schema(description = "Request path that produced the failure.", example = "/api/v1/tickets")
        String path,

        @Schema(description = "Present only for field-level validation failures, in which case it "
                + "carries one entry for every field that failed.")
        List<FieldError> fieldErrors) {

    public ErrorResponse {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(path, "path");
        // Absent and empty are the same thing to a client, so both normalize to omitted JSON.
        fieldErrors = (fieldErrors == null || fieldErrors.isEmpty()) ? null : List.copyOf(fieldErrors);
    }

    /** Builds a response with no field errors, deriving {@code status} and {@code error} from {@code status}. */
    public static ErrorResponse of(HttpStatus status, String message, String path) {
        return of(status, message, path, null);
    }

    /** Builds a response carrying one entry per failing field. */
    public static ErrorResponse of(
            HttpStatus status, String message, String path, List<FieldError> fieldErrors) {
        Objects.requireNonNull(status, "status");
        return new ErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(), message, path, fieldErrors);
    }
}
