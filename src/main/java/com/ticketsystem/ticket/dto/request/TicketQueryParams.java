package com.ticketsystem.ticket.dto.request;

import com.ticketsystem.ticket.domain.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Optional;

/**
 * Query parameters of {@code GET /api/v1/tickets} — pagination, keyword search, and status filter
 * (Requirements 2.1, 2.2, 2.4, 6.1, 6.4, 7.1, 7.3, 7.4).
 *
 * <p>Gathering the four parameters into one validated object is what makes the list endpoint fail closed:
 * the whole object is validated at the controller boundary, so an out-of-range {@code page} or an
 * over-long {@code keyword} is a 400 <em>before</em> any query runs and no ticket data can leak alongside
 * the error (Requirements 2.4, 6.4, 6.6, 7.3).
 *
 * <p>Absent parameters are defaulted in the canonical constructor rather than at the use site, so page 0 /
 * size 20 cannot drift between callers (Requirement 2.2). Defaulting only ever replaces a <em>missing</em>
 * value: a supplied {@code page=-1} stays {@code -1} and is rejected instead of being quietly corrected to
 * a valid page, which would return data for a request the client never made.
 *
 * <p>{@code status} is typed as the enum, so an unrecognized value fails during conversion and yields a
 * 400 (Requirement 7.3), while Spring's string-to-enum conversion maps an empty value to {@code null},
 * which is how {@code status=} comes to mean "no filter" (Requirement 7.4).
 *
 * <p>{@code keyword} is not normalized. An empty {@code keyword=} is a 400 rather than being folded into
 * "absent", because a client that asks to search for nothing has made a malformed request, not an
 * unfiltered one (Requirements 6.4, 6.6).
 */
@Schema(description = "Pagination, keyword search, and status filter parameters for the ticket list.")
public record TicketQueryParams(

        @Schema(description = "Zero-based page index. Defaults to 0 when omitted; a negative value is "
                + "rejected with 400.", example = "0", defaultValue = "0",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @NotNull
        @Min(value = 0, message = "must be 0 or greater")
        Integer page,

        @Schema(description = "Page size, 1 to 100. Defaults to 20 when omitted; a value outside the "
                + "range is rejected with 400.", example = "20", defaultValue = "20",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @NotNull
        @Min(value = 1, message = "must be 1 or greater")
        @Max(value = 100, message = "must be 100 or less")
        Integer size,

        @Schema(description = "Case-insensitive substring matched against title or description. Omit for "
                + "no keyword search; when supplied it must be 1 to 200 characters, so an empty keyword "
                + "is rejected with 400.", example = "sso",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @Size(min = 1, max = 200, message = "must be 1 to 200 characters")
        String keyword,

        @Schema(description = "Exact status to filter by. Omit, or send an empty value, for no status "
                + "filter; an unrecognized value is rejected with 400.", example = "OPEN",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        TicketStatus status) {

    /** Page index used when the client supplies none (Requirement 2.2). */
    public static final int DEFAULT_PAGE = 0;

    /** Page size used when the client supplies none (Requirement 2.2). */
    public static final int DEFAULT_SIZE = 20;

    /** Smallest accepted page size. */
    public static final int MIN_SIZE = 1;

    /** Largest accepted page size, bounding how much data one request can pull. */
    public static final int MAX_SIZE = 100;

    /** Smallest accepted keyword length. */
    public static final int MIN_KEYWORD_LENGTH = 1;

    /** Largest accepted keyword length. */
    public static final int MAX_KEYWORD_LENGTH = 200;

    public TicketQueryParams {
        // Only a missing value is defaulted; a supplied out-of-range value is left intact so the
        // constraints above can reject it rather than having it silently corrected.
        page = (page == null) ? DEFAULT_PAGE : page;
        size = (size == null) ? DEFAULT_SIZE : size;
    }

    /** The defaults-only query: first page, default size, no keyword, no status filter. */
    public static TicketQueryParams defaults() {
        return new TicketQueryParams(null, null, null, null);
    }

    /** @return the keyword when one was supplied, otherwise empty, for composing the search predicate. */
    public Optional<String> keywordFilter() {
        return Optional.ofNullable(keyword);
    }

    /** @return the status when one was supplied, otherwise empty, meaning every status is included. */
    public Optional<TicketStatus> statusFilter() {
        return Optional.ofNullable(status);
    }
}
