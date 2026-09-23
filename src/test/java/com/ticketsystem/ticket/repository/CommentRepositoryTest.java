package com.ticketsystem.ticket.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.ticket.domain.Comment;
import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * Persistence slice tests for {@link CommentRepository} (Req 3.1, 5.1, 9.1).
 *
 * <p>As with the entity slice tests, the Boot test datasource replacement is disabled so the queries
 * run against the schema the V1 Flyway migration produced rather than a Hibernate-generated one.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CommentRepositoryTest {

    /** Truncated to the microsecond precision of {@code TIMESTAMP WITH TIME ZONE}. */
    private static final Instant BASE_TIME = Instant.parse("2026-03-01T10:15:30.123456Z");

    /**
     * Databases order UUIDs as unsigned 128-bit values, whereas {@link UUID#compareTo} compares the
     * halves as signed longs. The expected {@code (createdAt, id)} order is built with unsigned
     * semantics so the assertion describes what the database will actually return.
     */
    private static final Comparator<UUID> UNSIGNED_UUID_ORDER = (left, right) -> {
        int mostSignificant =
                Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return mostSignificant != 0
                ? mostSignificant
                : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    };

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void shouldReturnCommentsOldestFirstWithIdBreakingTimestampTies() {
        Ticket ticket = newTicket("Badge reader offline");
        // Three comments share BASE_TIME, so the id tiebreaker is what fixes their relative order.
        ticket.addComment(newComment("carol@example.com", "Latest note.", BASE_TIME.plusSeconds(120)));
        ticket.addComment(newComment("alice@example.com", "Tie A.", BASE_TIME));
        ticket.addComment(newComment("dave@example.com", "Earliest note.", BASE_TIME.minusSeconds(120)));
        ticket.addComment(newComment("bob@example.com", "Tie B.", BASE_TIME));
        UUID ticketId = entityManager.persistAndGetId(ticket, UUID.class);
        List<UUID> expectedOrder = ticket.getComments().stream()
                .sorted(Comparator.comparing(Comment::getCreatedAt).thenComparing(Comment::getId, UNSIGNED_UUID_ORDER))
                .map(Comment::getId)
                .toList();
        flushAndClear();

        List<Comment> found = commentRepository.findByTicketIdOrderedOldestFirst(ticketId);

        assertThat(found).extracting(Comment::getId).containsExactlyElementsOf(expectedOrder);
        assertThat(found).extracting(Comment::getCreatedAt).isSorted();
        assertThat(found).extracting(Comment::getContent).startsWith("Earliest note.").endsWith("Latest note.");
    }

    @Test
    void shouldReturnOnlyTheRequestedTicketsComments() {
        Ticket wanted = newTicket("VPN drops every few minutes");
        wanted.addComment(newComment("alice@example.com", "Happens on the guest network too.", BASE_TIME));
        Ticket other = newTicket("Printer on floor 3 jams");
        other.addComment(newComment("bob@example.com", "Unrelated note.", BASE_TIME));
        UUID wantedId = entityManager.persistAndGetId(wanted, UUID.class);
        entityManager.persist(other);
        flushAndClear();

        List<Comment> found = commentRepository.findByTicketIdOrderedOldestFirst(wantedId);

        assertThat(found)
                .extracting(Comment::getContent)
                .containsExactly("Happens on the guest network too.");
        assertThat(found).allSatisfy(comment -> assertThat(comment.getTicket().getId()).isEqualTo(wantedId));
    }

    @Test
    void shouldReturnEmptyListForATicketWithoutComments() {
        UUID ticketId = entityManager.persistAndGetId(newTicket("Mailbox quota exceeded"), UUID.class);
        flushAndClear();

        assertThat(commentRepository.findByTicketIdOrderedOldestFirst(ticketId)).isEmpty();
    }

    @Test
    void shouldReturnEmptyListForAnUnknownTicketRatherThanFailing() {
        assertThat(commentRepository.findByTicketIdOrderedOldestFirst(UUID.randomUUID())).isEmpty();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static Ticket newTicket(String title) {
        Ticket ticket = new Ticket();
        ticket.setTitle(title);
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setPriority(TicketPriority.MEDIUM);
        ticket.setCreatedAt(BASE_TIME);
        ticket.setUpdatedAt(BASE_TIME);
        return ticket;
    }

    private static Comment newComment(String author, String content, Instant createdAt) {
        Comment comment = new Comment();
        comment.setAuthor(author);
        comment.setContent(content);
        comment.setCreatedAt(createdAt.truncatedTo(ChronoUnit.MICROS));
        return comment;
    }
}
