package com.ticketsystem.ticket.repository;

import com.ticketsystem.ticket.domain.Comment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for {@link Comment} records (Req 3.1, 5.1, 9.1).
 *
 * <p>Comments are only ever read in the scope of their owning ticket, so the finder below is the
 * single read entry point. The order is stated explicitly in JPQL rather than left to a derived
 * method name, because the {@code id} tiebreaker is the part that matters and it is easy to lose in
 * a long method name.
 */
@Repository
public interface CommentRepository extends JpaRepository<Comment, UUID> {

    /**
     * Returns a ticket's comments oldest first (Req 3.6, 5.7).
     *
     * <p>{@code id} is the secondary sort key so the order is total: several comments can share a
     * {@code createdAt}, and without the tiebreaker their relative order would be whatever the
     * database happened to return. This matches {@code @OrderBy("createdAt ASC, id ASC")} on
     * {@code Ticket.comments}, so a comment list read through either path comes back identical.
     *
     * @param ticketId identifier of the owning ticket
     * @return the ticket's comments ordered by {@code (createdAt, id)} ascending; empty when the
     *     ticket has no comments or does not exist — existence is the service's concern, not this
     *     query's
     */
    @Query("select c from Comment c where c.ticket.id = :ticketId order by c.createdAt asc, c.id asc")
    List<Comment> findByTicketIdOrderedOldestFirst(@Param("ticketId") UUID ticketId);
}
