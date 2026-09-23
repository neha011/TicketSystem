package com.ticketsystem.ticket.controller;

import com.ticketsystem.ticket.dto.request.CreateTicketRequest;
import com.ticketsystem.ticket.dto.request.StatusTransitionRequest;
import com.ticketsystem.ticket.dto.request.TicketQueryParams;
import com.ticketsystem.ticket.dto.request.UpdateTicketRequest;
import com.ticketsystem.ticket.dto.response.PagedResponse;
import com.ticketsystem.ticket.dto.response.TicketDetailResponse;
import com.ticketsystem.ticket.dto.response.TicketSummaryResponse;
import com.ticketsystem.ticket.service.TicketService;
import com.ticketsystem.ticket.service.TicketStatusTransitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * HTTP boundary for ticket resources (Requirements 1.6, 2.1, 2.4, 3.1, 3.3, 4.1, 8.2).
 *
 * <p>This controller holds no business rules. It maps requests to service calls, triggers
 * {@code jakarta.validation} on request bodies ({@code @Valid}) and query parameters
 * ({@code @Validated} on the class), derives the actor from the authenticated {@link Principal}, and
 * builds the status codes and {@code Location} headers. It makes no transition decisions and never
 * touches the transition table — status legality is decided in {@link TicketStatusTransitionService}.
 *
 * <p>The actor is taken from the authenticated principal, never from a request body, so a client
 * cannot act as, or attribute work to, someone else.
 */
@RestController
@RequestMapping("/api/v1/tickets")
@Validated
@Tag(name = "Tickets", description = "Create, list, read, update, and transition support tickets.")
public class TicketController {

    private final TicketService ticketService;
    private final TicketStatusTransitionService statusTransitionService;

    public TicketController(
            TicketService ticketService,
            TicketStatusTransitionService statusTransitionService) {
        this.ticketService = ticketService;
        this.statusTransitionService = statusTransitionService;
    }

    /**
     * Creates a ticket and returns it with a {@code Location} header pointing at the new resource
     * (Requirements 1.1, 1.6).
     *
     * @return 201 Created with the created ticket and {@code Location: /api/v1/tickets/{id}}
     */
    @Operation(
            summary = "Create a ticket",
            description = "Creates a ticket in the OPEN state and returns it with a Location header "
                    + "pointing at the new resource. Status is always OPEN on creation and is not "
                    + "accepted from the client.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Ticket created; Location header set."),
        @ApiResponse(
                responseCode = "400",
                description = "Missing/blank/over-long title, unknown priority, or unparseable JSON.",
                content = @Content),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication is missing or invalid.",
                content = @Content),
        @ApiResponse(
                responseCode = "403",
                description = "Authenticated but not permitted to create tickets.",
                content = @Content),
        @ApiResponse(
                responseCode = "500",
                description = "Persistence failure; the ticket is not reported as created.",
                content = @Content)
    })
    @PostMapping
    public ResponseEntity<TicketDetailResponse> create(
            @Valid @RequestBody CreateTicketRequest request,
            Principal principal,
            UriComponentsBuilder uriBuilder) {
        TicketDetailResponse created = ticketService.create(request, actor(principal));
        URI location = uriBuilder.path("/api/v1/tickets/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Lists tickets with pagination, keyword search, and status filter (Requirements 2.1, 2.4, 7.3,
     * 7.4).
     *
     * <p>{@code page}, {@code size}, and {@code keyword} are bound into {@link TicketQueryParams}; the
     * class-level {@code @Validated} plus {@code @Valid} here triggers their constraints, so an
     * out-of-range page, size, or keyword is rejected as a 400 before the service runs.
     *
     * <p>{@code status} is bound as a raw {@link String} rather than through Spring's lenient
     * String&rarr;enum query-parameter conversion, which coerces whitespace-padded names such as
     * {@code " OPEN"} to a valid enum value. The raw value is resolved at this boundary by
     * {@link TicketService#resolveStatusFilter} and again inside the service via the
     * {@link TicketService#list(TicketQueryParams, String, String) three-argument overload}, so the
     * match is exact and case-sensitive: an unrecognized value is a 400 stating the value is not
     * recognized (Requirement 7.3) while an absent or empty value means "no status filter"
     * (Requirement 7.4). The resolver is a pure, static check, so the boundary rejects a bad status
     * before any query runs and independently of the service's own re-check (Requirement 7.5). Any
     * {@code status} the record happens to bind is deliberately ignored in favour of the raw string.
     *
     * @return 200 OK with a page of ticket summaries and pagination metadata
     */
    @Operation(
            summary = "List tickets",
            description = "Returns a page of tickets with pagination metadata, optionally narrowed by "
                    + "a case-insensitive keyword and an exact status filter. A page at or beyond the "
                    + "last page returns 200 with empty content and coherent metadata, not an error.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of ticket summaries with metadata."),
        @ApiResponse(
                responseCode = "400",
                description = "Negative page, size outside 1-100, keyword outside 1-200 characters, "
                        + "or an unrecognized status; no ticket data is returned.",
                content = @Content),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication is missing or invalid.",
                content = @Content),
        @ApiResponse(
                responseCode = "403",
                description = "Authenticated but not permitted to list tickets.",
                content = @Content)
    })
    @GetMapping
    public PagedResponse<TicketSummaryResponse> list(
            @Valid TicketQueryParams params,
            @Parameter(
                            description =
                                    "Exact status to filter by. Omit, or send an empty value, for no "
                                            + "status filter; an unrecognized value is rejected with "
                                            + "400.",
                            example = "OPEN")
                    @RequestParam(name = "status", required = false)
                    String status,
            Principal principal) {
        // Reject an unrecognized status at the boundary (exact, case-sensitive) before any query runs,
        // so a lenient binder cannot coerce " OPEN"/"open" into a filter. The raw value is passed on so
        // the service re-checks it too (Requirements 7.3, 7.4, 7.5).
        TicketService.resolveStatusFilter(status);
        return ticketService.list(params, status, actor(principal));
    }

    /**
     * Returns one ticket with its comments (Requirements 3.1, 3.3).
     *
     * @return 200 OK with the full ticket representation
     */
    @Operation(
            summary = "Get a ticket by id",
            description = "Returns the full ticket representation including its comments, ordered "
                    + "oldest to newest.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The ticket and its comments."),
        @ApiResponse(
                responseCode = "400",
                description = "The id is not a well-formed UUID.",
                content = @Content),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication is missing or invalid; body carries no ticket data.",
                content = @Content),
        @ApiResponse(
                responseCode = "403",
                description = "Authenticated but not permitted; body carries no ticket data.",
                content = @Content),
        @ApiResponse(
                responseCode = "404",
                description = "Well-formed id, but no such ticket exists.",
                content = @Content)
    })
    @GetMapping("/{id}")
    public TicketDetailResponse getById(@PathVariable UUID id, Principal principal) {
        return ticketService.getById(id, actor(principal));
    }

    /**
     * Applies a partial field update (Requirements 4.1).
     *
     * @return 200 OK with the updated ticket
     */
    @Operation(
            summary = "Update ticket fields",
            description = "Applies a partial update over title, description, priority, and assignee; "
                    + "version is required. An omitted field is left unchanged, while an explicit null "
                    + "clears a nullable field. Status is not updatable here.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Ticket updated; updatedAt advanced."),
        @ApiResponse(
                responseCode = "400",
                description = "One or more fields failed validation; the body lists every failing "
                        + "field.",
                content = @Content),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication is missing or invalid.",
                content = @Content),
        @ApiResponse(
                responseCode = "403",
                description = "Authenticated but not permitted to modify the ticket.",
                content = @Content),
        @ApiResponse(
                responseCode = "404",
                description = "No such ticket; all records left unmodified.",
                content = @Content),
        @ApiResponse(
                responseCode = "409",
                description = "Supplied version does not match the stored version.",
                content = @Content),
        @ApiResponse(
                responseCode = "422",
                description = "Assignee is syntactically valid but names no existing user.",
                content = @Content)
    })
    @PatchMapping("/{id}")
    public TicketDetailResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTicketRequest request,
            Principal principal) {
        return ticketService.update(id, request, actor(principal));
    }

    /**
     * Applies a status transition (Requirements 8.2).
     *
     * <p>The controller only unpacks the request and delegates; the transition table is consulted in
     * the service, so no legality decision is made here.
     *
     * @return 200 OK with the transitioned ticket carrying its new status
     */
    @Operation(
            summary = "Transition ticket status",
            description = "Applies a status transition through the state machine. An undefined status "
                    + "name is a 400, while a defined name the transition table forbids (including a "
                    + "same-status request or one out of a terminal state) is a 409. The 409 message "
                    + "names both the current and requested status.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Transition applied; body carries the new "
                + "status."),
        @ApiResponse(
                responseCode = "400",
                description = "Status is absent or not a defined TicketStatus value.",
                content = @Content),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication is missing or invalid.",
                content = @Content),
        @ApiResponse(
                responseCode = "403",
                description = "Authenticated but not permitted to modify the ticket.",
                content = @Content),
        @ApiResponse(
                responseCode = "404",
                description = "No such ticket.",
                content = @Content),
        @ApiResponse(
                responseCode = "409",
                description = "Transition not permitted by the table, or a version/concurrent "
                        + "conflict; status left unchanged.",
                content = @Content)
    })
    @PatchMapping("/{id}/status")
    public TicketDetailResponse transitionStatus(
            @PathVariable UUID id,
            @Valid @RequestBody StatusTransitionRequest request,
            Principal principal) {
        return statusTransitionService.transitionStatus(
                id, request.status(), request.version(), actor(principal));
    }

    /**
     * @return the authenticated caller's identifier; a null principal is passed through as {@code null}
     *     so the service's fail-closed authorization answers 403 rather than the controller inventing a
     *     policy of its own.
     */
    private static String actor(Principal principal) {
        return (principal == null) ? null : principal.getName();
    }
}
