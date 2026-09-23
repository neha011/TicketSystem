package com.ticketsystem.ticket.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One page of results plus the pagination metadata every list endpoint returns
 * (Requirements 2.3, 2.5, 9.8).
 *
 * <p>A page at or beyond the last one is a normal, successful result: {@code content} is empty and the
 * metadata still describes the full result set, so the client gets a 200 rather than an error
 * (Requirement 2.5). An empty database behaves the same way, with {@code totalElements} and
 * {@code totalPages} both zero (Requirement 9.8).
 *
 * @param <T> the element type of {@code content}
 */
@Schema(description = "A page of results with pagination metadata.")
public record PagedResponse<T>(
        @Schema(description = "Elements on the requested page. Empty when the page lies at or "
                + "beyond the last page, or when no elements match.")
        List<T> content,

        @Schema(description = "Zero-based index of the returned page.", example = "0")
        int page,

        @Schema(description = "Requested page size, 1 to 100. An upper bound on the number of "
                + "elements in content, not a guarantee.", example = "20")
        int size,

        @Schema(description = "Total number of elements matching the query across all pages. "
                + "Independent of which page was requested.", example = "137")
        long totalElements,

        @Schema(description = "Total number of pages for the current total and page size, "
                + "ceil(totalElements / size). Zero when nothing matches.", example = "7")
        int totalPages) {

    public PagedResponse {
        content = (content == null) ? List.of() : List.copyOf(content);
    }

    /**
     * Builds a page, deriving {@code totalPages} from {@code totalElements} and {@code size} so the
     * two can never disagree.
     *
     * @throws IllegalArgumentException if {@code page} is negative, {@code size} is not positive, or
     *     {@code totalElements} is negative — all of which the request layer rejects as 400 before a
     *     query is ever run (Requirement 2.4)
     */
    public static <T> PagedResponse<T> of(List<T> content, int page, int size, long totalElements) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative, was " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be positive, was " + size);
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("totalElements must not be negative, was " + totalElements);
        }
        // Integer ceiling division; totalPages stays an int because size is at least 1.
        int totalPages = (int) ((totalElements + size - 1) / size);
        return new PagedResponse<>(content, page, size, totalElements, totalPages);
    }

    /** Builds a page with no content and no matches, used when nothing satisfies the query. */
    public static <T> PagedResponse<T> empty(int page, int size) {
        return of(List.of(), page, size, 0L);
    }
}
