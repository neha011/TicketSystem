package com.ticketsystem.ticket.dto.mapper;

import com.ticketsystem.ticket.domain.Comment;
import com.ticketsystem.ticket.dto.response.CommentResponse;
import com.ticketsystem.ticket.validation.TrimmedSizeValidator;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Explicit conversion between the {@link Comment} entity and {@link CommentResponse}
 * (Requirements 3.1, 5.2).
 *
 * <p>Hand-written rather than generated: the conversion is the boundary that keeps entities out of the
 * wire format, so it is worth being able to read it. The cost is that a dropped field is a silent
 * defect, which is why Property 15 checks the round trip field by field.
 *
 * <p>The mapping is total and symmetric over the four exposed fields — {@code id}, {@code author},
 * {@code content}, {@code createdAt}. {@code ticket} is deliberately absent from the DTO: a comment is
 * only ever returned inside its ticket's representation or from the ticket-scoped comment endpoint, so
 * the owning identifier is already known from the URL and echoing it would invite clients to treat it
 * as re-assignable.
 *
 * <p><strong>Requirements: 3.1, 5.2</strong>
 */
@Component
public class CommentMapper {

    /**
     * @param comment a persisted comment; all four mapped fields must be set, as they are all
     *     {@code NOT NULL} columns and {@link CommentResponse} rejects nulls
     * @return the wire representation of {@code comment}
     */
    public CommentResponse toResponse(Comment comment) {
        Objects.requireNonNull(comment, "comment");
        return new CommentResponse(
                comment.getId(),
                comment.getAuthor(),
                comment.getContent(),
                comment.getCreatedAt());
    }

    /**
     * Maps a list of comments, preserving order. Callers pass the collection already sorted — the
     * entity's {@code @OrderBy("createdAt ASC, id ASC")} does that — because re-sorting here would
     * duplicate the ordering rule in a second place where it could drift (Requirement 3.6).
     *
     * @param comments the comments to map; null is treated as none, so a ticket with no comments
     *     serializes as {@code []} rather than {@code null}
     * @return an immutable list in the same order as {@code comments}
     */
    public List<CommentResponse> toResponseList(List<Comment> comments) {
        if (comments == null || comments.isEmpty()) {
            return List.of();
        }
        return comments.stream().map(this::toResponse).toList();
    }

    /**
     * The inverse of {@link #toResponse}, rebuilding a detached {@link Comment} from its
     * representation.
     *
     * <p>The returned comment has <em>no</em> ticket association: the owning side is set by
     * {@code Ticket.addComment}, which keeps both directions of the relationship coherent. This exists
     * so the entity → DTO → entity round trip is expressible (Property 15) and so a caller holding a
     * representation can reconstruct the entity without going back to the database. It is not a
     * shortcut for persisting client-supplied comments — {@code CommentService} builds those, deriving
     * {@code author} from the authenticated principal (Requirement 5.6).
     *
     * @param response the representation to convert
     * @return a detached comment carrying the same four field values
     */
    public Comment toEntity(CommentResponse response) {
        Objects.requireNonNull(response, "response");
        Comment comment = new Comment();
        comment.setId(response.id());
        comment.setAuthor(response.author());
        comment.setContent(response.content());
        comment.setCreatedAt(response.createdAt());
        return comment;
    }

    /**
     * Builds a new, unsaved comment from its server-derived parts.
     *
     * <p>{@code author} and {@code createdAt} are parameters rather than fields read off a request DTO
     * because neither is client-supplied: the author comes from the authenticated principal and the
     * timestamp from the service (Requirements 5.1, 5.6). {@code content} is stored trimmed, matching
     * the entity's contract — validation has already measured the raw value by this point, so trimming
     * here cannot change a verdict (Requirement 5.8).
     *
     * <p>The ticket association is left to {@code Ticket.addComment} so both sides are set together.
     *
     * @param content the validated comment text, trimmed before storage
     * @param author the authenticated principal's identifier
     * @param createdAt the creation timestamp assigned by the service
     * @return a detached, unsaved comment with no identifier yet
     */
    public Comment toNewEntity(String content, String author, Instant createdAt) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(createdAt, "createdAt");
        Comment comment = new Comment();
        comment.setAuthor(author);
        comment.setContent(TrimmedSizeValidator.trim(content));
        comment.setCreatedAt(createdAt);
        return comment;
    }
}
