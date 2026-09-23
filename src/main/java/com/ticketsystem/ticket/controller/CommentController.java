package com.ticketsystem.ticket.controller;

import com.ticketsystem.ticket.dto.request.CreateCommentRequest;
import com.ticketsystem.ticket.dto.response.CommentResponse;
import com.ticketsystem.ticket.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * HTTP boundary for the comments belonging to a ticket (Requirements 3.1, 3.6, 5.2, 5.6).
 *
 * <p>Like {@link TicketController}, this controller holds no business rules. It maps requests to
 * {@link CommentService} calls, triggers {@code jakarta.validation} on the request body
 * ({@code @Valid}), derives the actor from the authenticated {@link Principal}, and constructs the
 * status codes and the {@code Location} header. Existence of the owning ticket (404), authorization
 * (403), and principal-derived authorship all live in the service, so every entry point inherits
 * them.
 *
 * <p>Comments are addressed under their owning ticket — {@code /api/v1/tickets/{id}/comments} — so
 * the URL itself scopes every read and write to one ticket and the author is never taken from the
 * body.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/comments")
@Tag(name = "Comments", description = "Add and read the comments belonging to a ticket.")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /**
     * Adds a comment to an existing ticket and returns it with a {@code Location} header pointing at
     * the new comment under its ticket (Requirements 5.2, 5.6).
     *
     * <p>The author is derived from the authenticated principal in the service, never from the body,
     * which carries no author field. A comment for a ticket that does not exist fails as a 404 in the
     * service before anything is written.
     *
     * @return 201 Created with the created comment and
     *     {@code Location: /api/v1/tickets/{ticketId}/comments/{commentId}}
     */
    @Operation(
            summary = "Add a comment to a ticket",
            description = "Creates a comment on an existing ticket. The author is derived from the "
                    + "authenticated principal and is never accepted from the body. The content is "
                    + "validated against its raw submitted value before any processing.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Comment created; Location header set."),
        @ApiResponse(
                responseCode = "400",
                description = "Content is absent, raw-blank, or exceeds 5000 characters after "
                        + "trimming.",
                content = @Content),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication is missing or invalid.",
                content = @Content),
        @ApiResponse(
                responseCode = "403",
                description = "Authenticated but not permitted to comment on the ticket.",
                content = @Content),
        @ApiResponse(
                responseCode = "404",
                description = "No such ticket to comment on.",
                content = @Content)
    })
    @PostMapping
    public ResponseEntity<CommentResponse> addComment(
            @PathVariable UUID ticketId,
            @Valid @RequestBody CreateCommentRequest request,
            Principal principal,
            UriComponentsBuilder uriBuilder) {
        CommentResponse created = commentService.addComment(ticketId, request, actor(principal));
        URI location = uriBuilder
                .path("/api/v1/tickets/{ticketId}/comments/{commentId}")
                .buildAndExpand(ticketId, created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Returns a ticket's comments ordered oldest to newest by {@code (createdAt, id)}
     * (Requirements 3.6, 5.6).
     *
     * <p>Reading through the ticket-scoped URL means a request for a non-existent ticket is a 404
     * rather than a silently empty list; the service enforces that and the ordering.
     *
     * @return 200 OK with the ticket's comments, oldest first; an empty list when it has none
     */
    @Operation(
            summary = "List a ticket's comments",
            description = "Returns the ticket's comments ordered oldest to newest by (createdAt, id). "
                    + "A request for a non-existent ticket is a 404 rather than a silently empty list.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The ticket's comments, oldest first; empty "
                + "when it has none."),
        @ApiResponse(
                responseCode = "401",
                description = "Authentication is missing or invalid.",
                content = @Content),
        @ApiResponse(
                responseCode = "403",
                description = "Authenticated but not permitted to view the ticket's comments.",
                content = @Content),
        @ApiResponse(
                responseCode = "404",
                description = "No such ticket.",
                content = @Content)
    })
    @GetMapping
    public List<CommentResponse> getComments(@PathVariable UUID ticketId, Principal principal) {
        return commentService.getComments(ticketId, actor(principal));
    }

    /**
     * @return the authenticated caller's identifier; a null principal is passed through as
     *     {@code null} so the service's fail-closed authorization answers 403 rather than the
     *     controller inventing a policy of its own.
     */
    private static String actor(Principal principal) {
        return (principal == null) ? null : principal.getName();
    }
}
