package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.domain.Comment;
import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.dto.mapper.CommentMapper;
import com.ticketsystem.ticket.dto.request.CreateCommentRequest;
import com.ticketsystem.ticket.dto.response.CommentResponse;
import com.ticketsystem.ticket.exception.NotFoundException;
import com.ticketsystem.ticket.repository.CommentRepository;
import com.ticketsystem.ticket.repository.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link CommentService}: owns the "the ticket must exist" check, the clock, the
 * principal-derived authorship rule, and the transaction boundary around every comment read and
 * write (Requirements 3.6, 5.1, 5.6, 5.8, 9.1).
 *
 * <p>The ordering mirrors {@link TicketServiceImpl}: load the ticket, authorize against it, and only
 * then build and persist. A request for a ticket that does not exist fails as a 404 before anything
 * is written (Requirement 5.6), and a denied caller can neither write nor read comments
 * (Requirement 3.5).
 *
 * <p>Two things are deliberately <em>not</em> read from the request body. The author comes from
 * {@code author} — the authenticated principal — so a client cannot attribute a comment to someone
 * else (Requirement 5.6). The content is stored trimmed, but the empty/whitespace verdict was already
 * reached against the raw submitted value at the API boundary; trimming here is storage cleanup, not
 * a second, sanitizing validation pass, so it cannot change whether the comment was accepted
 * (Requirement 5.8).
 */
@Service
public class CommentServiceImpl implements CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentServiceImpl.class);

    private final TicketRepository ticketRepository;
    private final CommentRepository commentRepository;
    private final CommentMapper commentMapper;
    private final TicketAuthorizationService authorizationService;
    private final Clock clock;

    public CommentServiceImpl(
            TicketRepository ticketRepository,
            CommentRepository commentRepository,
            CommentMapper commentMapper,
            TicketAuthorizationService authorizationService,
            Clock clock) {
        this.ticketRepository = Objects.requireNonNull(ticketRepository, "ticketRepository");
        this.commentRepository = Objects.requireNonNull(commentRepository, "commentRepository");
        this.commentMapper = Objects.requireNonNull(commentMapper, "commentMapper");
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * {@inheritDoc}
     *
     * <p>The write is wrapped in a transaction that commits before this method returns, so a caller
     * that receives a response knows the comment is durable and a persistence failure surfaces as a
     * failure rather than a reported success (Requirement 9.1).
     *
     * <p>The ticket is loaded first so a comment for a non-existent ticket is a 404 that writes
     * nothing (Requirement 5.6); authorization runs against the loaded ticket, before the comment is
     * built, so a denied request never reaches the database (Requirement 3.5). The comment is attached
     * through {@link Ticket#addComment} so both sides of the association are set together, then
     * persisted directly through the comment repository, which returns it with its generated
     * identifier.
     */
    @Override
    @Transactional
    public CommentResponse addComment(UUID ticketId, CreateCommentRequest request, String author) {
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(request, "request");

        Ticket ticket = requireTicket(ticketId);
        authorizationService.requireCanComment(author, ticket);

        Instant now = clock.instant();
        // author from the principal, never the body; content stored trimmed after validation
        // has already measured the raw value (Requirements 5.6, 5.8).
        Comment comment = commentMapper.toNewEntity(request.content(), author, now);
        ticket.addComment(comment);

        Comment saved = commentRepository.save(comment);
        log.info("Comment {} added to ticket {} by {}", saved.getId(), ticketId, author);
        return commentMapper.toResponse(saved);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Read-only transaction. The ticket existence check runs first so a read for a non-existent
     * ticket is a 404 rather than a silently empty list (Requirement 5.6), and authorization runs
     * against the loaded ticket before any comment is returned (Requirement 3.5). The ordering is the
     * repository's — {@code (createdAt, id)} ascending — so it is stated in exactly one place
     * (Requirement 3.6).
     */
    @Override
    @Transactional(readOnly = true)
    public List<CommentResponse> getComments(UUID ticketId, String actor) {
        Objects.requireNonNull(ticketId, "ticketId");

        Ticket ticket = requireTicket(ticketId);
        authorizationService.requireCanView(actor, ticket);

        List<Comment> comments = commentRepository.findByTicketIdOrderedOldestFirst(ticketId);
        return commentMapper.toResponseList(comments);
    }

    /**
     * Loads a ticket or fails with a 404 (Requirement 5.6).
     *
     * <p>The plain {@code findById} is used rather than {@code findWithCommentsById}: neither entry
     * point needs the comment collection eagerly — {@code addComment} appends one and lets the cascade
     * persist it, and {@code getComments} reads the ordered collection through its own query — so
     * fetching the whole graph here would be wasted work.
     */
    private Ticket requireTicket(UUID ticketId) {
        return ticketRepository
                .findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket " + ticketId + " not found"));
    }
}
