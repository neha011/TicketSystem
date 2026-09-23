package com.ticketsystem.ticket.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.ticket.domain.Comment;
import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

/**
 * Persistence slice tests for {@link TicketRepository} (Req 2.1, 3.1, 9.1).
 *
 * <p>These cover the repository surface itself: a persisted ticket is readable by id, and the
 * inherited {@code JpaSpecificationExecutor} paginates a specification-backed query. The actual
 * search and filter predicates are the concern of {@code TicketSpecifications} and are tested with
 * it.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TicketRepositoryTest {

    private static final Instant BASE_TIME = Instant.parse("2026-03-01T10:15:30.123456Z");

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void shouldPersistATicketAndReadItBackById() {
        Ticket saved = ticketRepository.save(newTicket("Laptop will not boot", TicketStatus.OPEN));
        flushAndClear();

        assertThat(saved.getId()).isNotNull();
        assertThat(ticketRepository.findById(saved.getId()))
                .get()
                .satisfies(found -> {
                    assertThat(found.getTitle()).isEqualTo("Laptop will not boot");
                    assertThat(found.getStatus()).isEqualTo(TicketStatus.OPEN);
                });
    }

    @Test
    void shouldReturnEmptyOptionalForAnUnknownIdentifier() {
        assertThat(ticketRepository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void shouldLoadATicketWithItsCommentsInitializedAndOrderedOldestFirst() {
        Ticket ticket = newTicket("Printer jams", TicketStatus.OPEN);
        ticket.addComment(newComment("second", BASE_TIME.plusSeconds(60)));
        ticket.addComment(newComment("first", BASE_TIME));
        UUID id = ticketRepository.save(ticket).getId();
        flushAndClear();

        Ticket found = ticketRepository.findWithCommentsById(id).orElseThrow();

        // Readable after the session is detached only because the graph was fetched eagerly.
        entityManager.clear();
        assertThat(found.getComments()).extracting(Comment::getContent).containsExactly("first", "second");
    }

    @Test
    void shouldReturnEmptyOptionalFromTheCommentGraphFinderForAnUnknownIdentifier() {
        assertThat(ticketRepository.findWithCommentsById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void shouldPaginateASpecificationBackedQuery() {
        ticketRepository.saveAll(List.of(
                newTicket("Open one", TicketStatus.OPEN),
                newTicket("Open two", TicketStatus.OPEN),
                newTicket("Open three", TicketStatus.OPEN),
                newTicket("Closed one", TicketStatus.CLOSED)));
        flushAndClear();
        Specification<Ticket> openOnly =
                (root, query, builder) -> builder.equal(root.get("status"), TicketStatus.OPEN);

        Page<Ticket> firstPage = ticketRepository.findAll(openOnly, PageRequest.of(0, 2));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).allSatisfy(ticket -> assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static Ticket newTicket(String title, TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setTitle(title);
        ticket.setStatus(status);
        ticket.setPriority(TicketPriority.MEDIUM);
        ticket.setCreatedAt(BASE_TIME);
        ticket.setUpdatedAt(BASE_TIME);
        return ticket;
    }

    private static Comment newComment(String content, Instant createdAt) {
        Comment comment = new Comment();
        comment.setAuthor("alice");
        comment.setContent(content);
        comment.setCreatedAt(createdAt);
        return comment;
    }
}
