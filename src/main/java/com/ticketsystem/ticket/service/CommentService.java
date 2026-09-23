package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.dto.request.CreateCommentRequest;
import com.ticketsystem.ticket.dto.response.CommentResponse;
import com.ticketsystem.ticket.exception.ForbiddenException;
import com.ticketsystem.ticket.exception.NotFoundException;
import java.util.List;
import java.util.UUID;

/**
 * Comment business logic: existence of the owning ticket, authorization, principal-derived
 * authorship, and the transaction boundary around every comment read and write
 * (Requirements 3.6, 5.1, 5.6, 5.8, 9.1).
 *
 * <p>As with {@link TicketService}, {@code author}/{@code actor} is the identifier already derived
 * from the authenticated principal and is passed in explicitly, so this layer never touches a
 * {@code Principal} or {@code SecurityContext}. Authorization is enforced here rather than in the
 * controller so every entry point inherits the check (Requirement 3.5).
 *
 * <p>Callers receive DTOs only; entities never leave this layer.
 */
public interface CommentService {

    /**
     * Creates a comment on an existing ticket and persists it before returning
     * (Requirements 5.1, 5.6, 5.8, 9.1).
     *
     * <p>The comment's {@code author} is taken from {@code author} — the authenticated principal —
     * never from the request body, which carries no author field at all (Requirement 5.6). The
     * {@code content} has already had its trimmed length judged against the raw submitted value at the
     * API boundary; the service stores it trimmed, so no sanitization runs ahead of that judgment
     * (Requirement 5.8).
     *
     * @param ticketId identifier of the ticket to attach the comment to
     * @param request the already-validated creation request; {@code content} is present with a
     *     trimmed length of 1 to 5000 characters
     * @param author identifier of the authenticated caller, used as the comment's author
     * @return the created comment, including its assigned identifier and creation timestamp
     * @throws NotFoundException (404) if no ticket has this identifier (Requirement 5.6)
     * @throws ForbiddenException (403) if the caller may not comment on this ticket (Requirement 3.5)
     */
    CommentResponse addComment(UUID ticketId, CreateCommentRequest request, String author);

    /**
     * Returns a ticket's comments oldest first, ordered by {@code (createdAt, id)}
     * (Requirements 3.6, 9.1).
     *
     * <p>The secondary {@code id} key makes the order total, so comments sharing a {@code createdAt}
     * still come back in a stable sequence.
     *
     * @param ticketId identifier of the ticket whose comments to read
     * @param actor identifier of the authenticated caller
     * @return the ticket's comments oldest first; empty when the ticket has no comments
     * @throws NotFoundException (404) if no ticket has this identifier (Requirement 5.6)
     * @throws ForbiddenException (403) if the caller may not view this ticket (Requirement 3.5)
     */
    List<CommentResponse> getComments(UUID ticketId, String actor);
}
