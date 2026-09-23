package com.ticketsystem.ticket.repository;

import com.ticketsystem.ticket.domain.Ticket;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for {@link Ticket} aggregates (Req 2.1, 3.1, 9.1).
 *
 * <p>{@link JpaSpecificationExecutor} is inherited so keyword search and status filtering can be
 * composed as {@code Specification}s rather than assembled as query strings — predicates stay
 * parameterized by construction, which is what keeps user-supplied search terms out of the query
 * text. The specifications themselves live in {@code TicketSpecifications}; this interface only
 * provides the execution surface and carries no business rules.
 */
@Repository
public interface TicketRepository extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {

    /**
     * Loads one ticket together with its comments in a single query (Req 3.1, 3.6).
     *
     * <p>The detail read needs the comments every time, so fetching them eagerly here avoids the
     * second query a lazy collection would trigger. It also means the returned aggregate is complete
     * the moment the transaction ends: with {@code spring.jpa.open-in-view: false} there is no open
     * session during response rendering, so a collection left as a proxy would fail after the status
     * line was already committed rather than as a clean 500.
     *
     * <p>The {@code @OrderBy("createdAt ASC, id ASC")} declared on {@code Ticket.comments} still
     * applies to the fetched collection, so the comment order is the same through this path as
     * through {@code CommentRepository}.
     *
     * @param id identifier of the ticket
     * @return the ticket with its comments initialized, or empty when no ticket has this identifier
     */
    @EntityGraph(attributePaths = "comments")
    Optional<Ticket> findWithCommentsById(UUID id);
}
