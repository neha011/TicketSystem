package com.ticketsystem.ticket.dto.mapper;

import com.ticketsystem.ticket.domain.Comment;
import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.request.CreateTicketRequest;
import com.ticketsystem.ticket.dto.response.CommentResponse;
import com.ticketsystem.ticket.dto.response.TicketDetailResponse;
import com.ticketsystem.ticket.dto.response.TicketSummaryResponse;
import com.ticketsystem.ticket.validation.TrimmedSizeValidator;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Explicit conversion between the {@link Ticket} entity and its wire representations
 * (Requirements 1.6, 2.6, 3.1, 5.2).
 *
 * <p>This class is the only place a {@code Ticket} is allowed to turn into something a controller may
 * return. Keeping it hand-written means a newly added entity column cannot leak into the API by
 * accident — but it also means a forgotten field is a silent defect, so the entity → DTO → entity round
 * trip is asserted field by field as Property 15.
 *
 * <p>The round trip is expressible because {@link #toDetail} and {@link #toEntity} are exact inverses
 * over every persisted field, including {@code version} and the comment collection.
 * {@link #toSummary} is the same mapping with {@code description} and {@code comments} projected away,
 * so it is deliberately <em>not</em> invertible.
 *
 * <p>Requests are a separate, non-invertible direction: {@link #toNewEntity} builds an unsaved ticket
 * from client input, and the fields a client cannot supply — identifier, status, timestamps, version —
 * come from the service rather than the body.
 *
 * <p>Stateless and thread-safe; registered as a bean so services can take it by constructor injection
 * rather than reaching for a static helper.
 *
 * <p><strong>Requirements: 1.6, 2.6, 3.1, 5.2</strong>
 */
@Component
public class TicketMapper {

    private final CommentMapper commentMapper;

    public TicketMapper(CommentMapper commentMapper) {
        this.commentMapper = Objects.requireNonNull(commentMapper, "commentMapper");
    }

    /**
     * Maps a ticket to the narrow list representation (Requirement 2.6).
     *
     * <p>{@code description} and {@code comments} are dropped: the list view renders neither, and
     * loading comments for every row of a page would be a query per row. {@code version} is kept so a
     * client can act on a row without re-fetching it.
     *
     * @param ticket a persisted ticket
     * @return the summary representation
     */
    public TicketSummaryResponse toSummary(Ticket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        return new TicketSummaryResponse(
                ticket.getId(),
                ticket.getTitle(),
                ticket.getStatus(),
                ticket.getPriority(),
                ticket.getAssignee(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                ticket.getVersion());
    }

    /**
     * Maps a page's worth of tickets, preserving the order the repository returned them in — the
     * default {@code createdAt DESC, id DESC} sort is the repository's contract and is not re-applied
     * here, where a second copy of the rule could drift from the first.
     *
     * @param tickets the tickets to map; null or empty yields an empty list, so an empty result page
     *     serializes as {@code []}
     * @return an immutable list in input order
     */
    public List<TicketSummaryResponse> toSummaryList(List<Ticket> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return List.of();
        }
        return tickets.stream().map(this::toSummary).toList();
    }

    /**
     * Maps a ticket to the full representation returned by create, detail, update, and status
     * transition (Requirements 1.6, 3.1, 5.2).
     *
     * <p>Comments are mapped in the order the entity exposes them, which its
     * {@code @OrderBy("createdAt ASC, id ASC")} fixes as oldest to newest (Requirement 3.6). The caller
     * must have loaded them — invoking this outside an open persistence context on a ticket whose
     * collection is still a lazy proxy is a programming error that will surface as a lazy-initialization
     * failure, not a silently empty list.
     *
     * @param ticket a persisted ticket with its comments loaded
     * @return the detail representation, with {@code comments} empty rather than null when there are
     *     none
     */
    public TicketDetailResponse toDetail(Ticket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        return new TicketDetailResponse(
                ticket.getId(),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getStatus(),
                ticket.getPriority(),
                ticket.getAssignee(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                ticket.getVersion(),
                commentMapper.toResponseList(ticket.getComments()));
    }

    /**
     * The inverse of {@link #toDetail}, rebuilding a detached {@link Ticket} from its representation.
     *
     * <p>Nothing here trims or normalizes: the values came from a stored entity, which the request layer
     * already trimmed, so touching them again would make the round trip lossy for a legitimately
     * space-bearing title such as {@code "a b"} and would break Property 15 for boundary-length values.
     *
     * <p>Comments are attached through {@link Ticket#addComment}, so each comment's back-reference is
     * set too and the reconstructed graph is coherent in both directions.
     *
     * <p>This exists to make the round trip expressible and to let a caller holding a representation
     * reconstruct the entity without a database read. It is <em>not</em> a way to persist
     * client-supplied state: the service loads the stored ticket and applies changes to it, so a client
     * cannot dictate {@code id}, {@code version}, or the timestamps.
     *
     * @param response the representation to convert
     * @return a detached ticket carrying every field value from {@code response}
     */
    public Ticket toEntity(TicketDetailResponse response) {
        Objects.requireNonNull(response, "response");
        Ticket ticket = new Ticket();
        ticket.setId(response.id());
        ticket.setTitle(response.title());
        ticket.setDescription(response.description());
        ticket.setStatus(response.status());
        ticket.setPriority(response.priority());
        ticket.setAssignee(response.assignee());
        ticket.setCreatedAt(response.createdAt());
        ticket.setUpdatedAt(response.updatedAt());
        ticket.setVersion(response.version());
        for (CommentResponse comment : response.comments()) {
            ticket.addComment(commentMapper.toEntity(comment));
        }
        return ticket;
    }

    /**
     * Builds a new, unsaved ticket from a creation request (Requirement 1.1).
     *
     * <p>{@code initialStatus} and {@code timestamp} are parameters rather than values invented here:
     * the "new tickets start OPEN" rule and the clock both belong to the service, so the mapper stays a
     * pure field-copying function that a test can drive with a fixed instant. {@code createdAt} and
     * {@code updatedAt} are set from the same instant so a never-modified ticket reports them equal.
     *
     * <p>{@code title} is stored trimmed, matching the entity's contract and the database's
     * {@code btrim} check. Trimming cannot change a validation verdict at this point — the constraint
     * measured the raw value before the request reached the service (Requirement 5.8) — and it uses the
     * same whitespace definition as that constraint, so a value that passed validation always fits its
     * column. {@code description} is left exactly as submitted: it has no trimmed-length rule, and
     * silently rewriting a body of text the user typed is not the mapper's call.
     *
     * <p>No identifier is assigned; JPA generates it on persist. No version is set; the persistence
     * layer owns it.
     *
     * @param request the validated creation request
     * @param initialStatus the status a new ticket starts in, decided by the service
     * @param timestamp the creation instant, used for both {@code createdAt} and {@code updatedAt}
     * @return a detached, unsaved ticket with no comments
     */
    public Ticket toNewEntity(CreateTicketRequest request, TicketStatus initialStatus, Instant timestamp) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(initialStatus, "initialStatus");
        Objects.requireNonNull(timestamp, "timestamp");
        Ticket ticket = new Ticket();
        ticket.setTitle(TrimmedSizeValidator.trim(request.title()));
        ticket.setDescription(request.description());
        ticket.setStatus(initialStatus);
        ticket.setPriority(request.priority());
        ticket.setAssignee(request.assignee());
        ticket.setCreatedAt(timestamp);
        ticket.setUpdatedAt(timestamp);
        return ticket;
    }

    /**
     * Convenience overload mapping a single comment, so a service handling comment creation does not
     * have to inject {@link CommentMapper} alongside this one.
     *
     * @param comment a persisted comment
     * @return the comment's wire representation
     */
    public CommentResponse toCommentResponse(Comment comment) {
        return commentMapper.toResponse(comment);
    }
}
