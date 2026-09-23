package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.request.CreateTicketRequest;
import com.ticketsystem.ticket.dto.request.TicketQueryParams;
import com.ticketsystem.ticket.dto.request.UpdateTicketRequest;
import com.ticketsystem.ticket.exception.ConflictException;
import com.ticketsystem.ticket.dto.response.PagedResponse;
import com.ticketsystem.ticket.dto.response.TicketDetailResponse;
import com.ticketsystem.ticket.dto.response.TicketSummaryResponse;
import com.ticketsystem.ticket.exception.ForbiddenException;
import com.ticketsystem.ticket.exception.NotFoundException;
import com.ticketsystem.ticket.exception.UnprocessableEntityException;
import com.ticketsystem.ticket.dto.response.FieldError;
import com.ticketsystem.ticket.exception.ValidationException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ticket business logic: lifecycle rules, authorization, and transaction boundaries
 * (Requirements 1.1, 1.2, 3.1, 3.5, 9.1).
 *
 * <p>Every method takes the {@code actor} — the identifier already derived from the authenticated
 * principal — as an explicit parameter. The service layer therefore never touches a {@code Principal},
 * a {@code SecurityContext}, or any other servlet type, and authorization is enforced here rather than
 * in the controller so a scheduled job or message consumer calling the same method inherits the same
 * check (Requirement 3.5).
 *
 * <p>Callers receive DTOs only; entities never leave this layer.
 */
public interface TicketService {

    /**
     * Creates a ticket in status {@code OPEN} and persists it before returning
     * (Requirements 1.1, 1.2, 9.1).
     *
     * @param request the already-validated creation request; field-shape rules (trimmed title length,
     *     description length, known priority) were enforced at the API boundary
     * @param actor identifier of the authenticated caller
     * @return the created ticket, including its assigned identifier, timestamps, and version
     * @throws ForbiddenException (403) if the caller is not permitted to create this ticket
     * @throws UnprocessableEntityException (422) if {@code assignee} is present but names no existing
     *     user (Requirement 4.5)
     */
    TicketDetailResponse create(CreateTicketRequest request, String actor);

    /**
     * Returns one page of the ticket list, newest ticket first, narrowed by the optional keyword
     * search and status filter (Requirements 2.1, 2.2, 2.3, 2.5, 6.1, 6.2, 6.5, 7.1, 7.2, 7.4, 9.8).
     *
     * <p>{@code status} is typed as the enum, so an undefined filter value is not representable on
     * this path at all. A status that is absent or {@code null} means "unfiltered" (Requirement 7.4),
     * as does an absent or empty keyword.
     *
     * <p>A page at or beyond the last one is a successful, empty result rather than an error, and the
     * metadata still describes the whole result set (Requirements 2.5, 9.8).
     *
     * @param params pagination, keyword, and status filter; {@code null} is the defaults-only query
     *     (page 0, size 20)
     * @param actor identifier of the authenticated caller
     * @return the requested page plus {@code page}, {@code size}, {@code totalElements}, and
     *     {@code totalPages} (Requirement 2.3)
     * @throws ValidationException (400) if {@code page}, {@code size}, or {@code keyword} is outside
     *     its accepted range — the query is never run, so no ticket data accompanies the rejection
     *     (Requirements 2.4, 6.4, 6.6)
     * @throws ForbiddenException (403) if the caller is not permitted to list tickets
     */
    PagedResponse<TicketSummaryResponse> list(TicketQueryParams params, String actor);

    /**
     * Same as {@link #list(TicketQueryParams, String)} but resolving the status filter from a raw,
     * unvalidated string (Requirements 7.4, 7.5).
     *
     * <p>This overload exists so the enum check is enforced <em>here</em> and not only at the API
     * boundary: a caller that never passed through query-parameter conversion — another service, a
     * scheduled job, a message consumer — still cannot list tickets with a status value the domain
     * does not define. The request is rejected before any query runs, so zero tickets are returned and
     * no ticket outside the requested filter can be returned (Requirement 7.5).
     *
     * @param params pagination and keyword; its typed {@code status} is ignored in favour of
     *     {@code rawStatus}
     * @param rawStatus the status filter as submitted; {@code null} or the empty string means
     *     unfiltered, while a whitespace-only or otherwise unrecognized value is rejected
     *     (Requirements 7.3, 7.4)
     * @param actor identifier of the authenticated caller
     * @return the requested page
     * @throws ValidationException (400) if {@code rawStatus} is neither blank nor the exact name of a
     *     defined {@link TicketStatus} (Requirement 7.5)
     */
    PagedResponse<TicketSummaryResponse> list(TicketQueryParams params, String rawStatus, String actor);

    /**
     * Resolves a raw status filter value to a {@link TicketStatus}, or to "unfiltered".
     *
     * <p>The match is exact and case-sensitive (Requirement 7.1): {@code "open"}, {@code "Open"}, and
     * {@code " OPEN"} are unrecognized, not lenient spellings of {@code OPEN}. Accepting them would
     * make the filter's behaviour depend on how the client happened to capitalize it.
     *
     * <p>"Unfiltered" is exactly the absent value or the empty string (Requirement 7.4). A
     * whitespace-only value such as {@code " "} is neither the empty string nor the exact name of a
     * defined status, so it is rejected as unrecognized rather than quietly treated as "no filter"
     * (Requirement 7.3). Matching is never preceded by trimming, so a blank-but-nonempty value cannot
     * be smuggled past the exact check.
     *
     * @param rawStatus the value as submitted
     * @return the status to filter on, or empty when {@code rawStatus} is null or the empty string
     *     (Requirement 7.4)
     * @throws ValidationException (400) if the value is present and non-empty but names no defined
     *     status — a whitespace-only value is unrecognized, not "unfiltered" (Requirement 7.3)
     */
    static Optional<TicketStatus> resolveStatusFilter(String rawStatus) {
        if (rawStatus == null || rawStatus.isEmpty()) {
            return Optional.empty();
        }
        for (TicketStatus candidate : TicketStatus.values()) {
            if (candidate.name().equals(rawStatus)) {
                return Optional.of(candidate);
            }
        }
        throw new ValidationException(
                "Status filter value is not recognized",
                List.of(
                        new FieldError(
                                "status", "must be one of " + Arrays.toString(TicketStatus.values()))));
    }

    /**
     * Returns one ticket with its comments, oldest comment first (Requirements 3.1, 3.6).
     *
     * @param id identifier of the ticket to read; a malformed identifier is rejected at the API
     *     boundary and cannot reach here
     * @param actor identifier of the authenticated caller
     * @return the full representation of the ticket
     * @throws NotFoundException (404) if no ticket has this identifier (Requirement 3.2)
     * @throws ForbiddenException (403) if the caller may not view this ticket (Requirement 3.5)
     */
    TicketDetailResponse getById(UUID id, String actor);

    /**
     * Applies a partial field update and advances {@code updatedAt} (Requirements 4.1, 4.2, 4.6).
     *
     * <p>Only {@code title}, {@code description}, {@code priority}, and {@code assignee} are
     * updatable. {@code status} is deliberately not part of {@link UpdateTicketRequest}, so this path
     * cannot move a ticket through the lifecycle and bypass the transition table — status changes go
     * through {@code TicketStatusTransitionService} (Requirements 8.1-8.6).
     *
     * <p>An omitted field is left exactly as stored; an explicitly null {@code assignee} clears the
     * assignment (Requirement 4.2). Every check runs before the entity is touched, so a rejected
     * request leaves the ticket — and every other record — unmodified (Requirements 4.3-4.6, 10.5).
     *
     * @param id identifier of the ticket to update
     * @param request the already-validated update request, including the expected {@code version}
     * @param actor identifier of the authenticated caller
     * @return the updated ticket, carrying the new version the client must round-trip next time
     * @throws NotFoundException (404) if no ticket has this identifier (Requirement 4.4)
     * @throws ForbiddenException (403) if the caller may not modify this ticket (Requirement 3.5)
     * @throws ConflictException (409) if {@code request.version()} does not match the stored version,
     *     meaning the ticket was modified since the caller read it (Requirement 4.6)
     * @throws UnprocessableEntityException (422) if {@code assignee} is supplied non-null but names no
     *     existing user (Requirement 4.5)
     */
    TicketDetailResponse update(UUID id, UpdateTicketRequest request, String actor);
}
