package com.ticketsystem.ticket.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import jakarta.persistence.EntityManager;
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
 * Persistence slice tests for {@link Ticket} and {@link Comment} (Req 3.6, 9.1, 9.3).
 *
 * <p>The datasource is deliberately <em>not</em> replaced by the Boot test database: these run
 * against the {@code test} profile datasource, where the V1 Flyway migration owns the schema and
 * Hibernate only validates it. That means every assertion here is made against the same DDL a
 * deployed instance gets, so a mapping that drifts from the migration fails the slice rather than
 * silently passing against a Hibernate-generated schema.
 *
 * <p>Each test flushes and clears the persistence context before re-reading, so assertions describe
 * what the database returned rather than what the first-level cache still had lying around.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TicketPersistenceTest {

    /**
     * Column precision for {@code TIMESTAMP WITH TIME ZONE} is microseconds, so test instants are
     * truncated to microseconds. Otherwise a nanosecond-precision {@link Instant#now()} would come
     * back rounded and the round-trip assertions would be testing the clock, not the mapping.
     */
    private static final Instant BASE_TIME = Instant.parse("2026-03-01T10:15:30.123456Z");

    /**
     * Databases compare UUIDs as unsigned 128-bit values (PostgreSQL compares the raw bytes), which
     * is not what {@link UUID#compareTo} does — that one compares the halves as signed longs. The
     * expected {@code (createdAt, id)} order is therefore built with unsigned semantics.
     */
    private static final Comparator<UUID> UNSIGNED_UUID_ORDER = (left, right) -> {
        int mostSignificant =
                Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return mostSignificant != 0
                ? mostSignificant
                : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    };

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void shouldRoundTripEveryTicketFieldThroughTheDatabase() {
        Ticket ticket = newTicket("Printer on floor 3 jams", TicketPriority.HIGH, "alice@example.com");
        ticket.setDescription("Paper feed grinds and stops after roughly ten pages.");

        UUID id = entityManager.persistAndGetId(ticket, UUID.class);
        flushAndClear();
        Ticket reloaded = entityManager.find(Ticket.class, id);

        assertThat(reloaded).isNotSameAs(ticket);
        assertThat(reloaded.getId()).isEqualTo(id);
        assertThat(reloaded.getTitle()).isEqualTo("Printer on floor 3 jams");
        assertThat(reloaded.getDescription())
                .isEqualTo("Paper feed grinds and stops after roughly ten pages.");
        assertThat(reloaded.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(reloaded.getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(reloaded.getAssignee()).isEqualTo("alice@example.com");
        assertThat(reloaded.getCreatedAt()).isEqualTo(BASE_TIME);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(BASE_TIME);
        assertThat(reloaded.getVersion()).isZero();
    }

    @Test
    void shouldStoreAnUnassignedTicketWithANullAssignee() {
        Ticket ticket = newTicket("VPN drops every few minutes", TicketPriority.MEDIUM, null);
        ticket.setDescription(null);

        UUID id = entityManager.persistAndGetId(ticket, UUID.class);
        flushAndClear();
        Ticket reloaded = entityManager.find(Ticket.class, id);

        assertThat(reloaded.getAssignee()).isNull();
        assertThat(reloaded.getDescription()).isNull();
    }

    @Test
    void shouldCascadePersistCommentsAndKeepThemAssociatedWithTheirTicket() {
        Ticket ticket = newTicket("Laptop will not boot", TicketPriority.CRITICAL, "bob@example.com");
        ticket.addComment(newComment("alice@example.com", "Reproduced on a cold start.", BASE_TIME));
        ticket.addComment(
                newComment("bob@example.com", "Swapped the power supply, still dead.", BASE_TIME.plusSeconds(60)));

        UUID ticketId = entityManager.persistAndGetId(ticket, UUID.class);
        flushAndClear();
        Ticket reloaded = entityManager.find(Ticket.class, ticketId);

        assertThat(reloaded.getComments()).hasSize(2);
        assertThat(reloaded.getComments())
                .allSatisfy(comment -> {
                    assertThat(comment.getId()).isNotNull();
                    assertThat(comment.getTicket().getId()).isEqualTo(ticketId);
                });
        assertThat(reloaded.getComments())
                .extracting(Comment::getAuthor, Comment::getContent, Comment::getCreatedAt)
                .containsExactly(
                        tuple("alice@example.com", "Reproduced on a cold start.", BASE_TIME),
                        tuple(
                                "bob@example.com",
                                "Swapped the power supply, still dead.",
                                BASE_TIME.plusSeconds(60)));
    }

    @Test
    void shouldReturnCommentsOldestFirstOrderedByCreatedAtThenIdWhenTimestampsTie() {
        Ticket ticket = newTicket("Mailbox quota exceeded", TicketPriority.LOW, null);
        // Three comments share BASE_TIME, so the (createdAt, id) secondary key is what decides their
        // relative order; the other two pin down the primary createdAt ordering.
        ticket.addComment(newComment("carol@example.com", "Latest note.", BASE_TIME.plusSeconds(120)));
        ticket.addComment(newComment("alice@example.com", "Tie A.", BASE_TIME));
        ticket.addComment(newComment("dave@example.com", "Earliest note.", BASE_TIME.minusSeconds(120)));
        ticket.addComment(newComment("bob@example.com", "Tie B.", BASE_TIME));
        ticket.addComment(newComment("erin@example.com", "Tie C.", BASE_TIME));

        UUID ticketId = entityManager.persistAndGetId(ticket, UUID.class);
        List<UUID> expectedOrder = ticket.getComments().stream()
                .sorted(Comparator.comparing(Comment::getCreatedAt).thenComparing(Comment::getId, UNSIGNED_UUID_ORDER))
                .map(Comment::getId)
                .toList();
        flushAndClear();
        List<Comment> reloadedComments = entityManager.find(Ticket.class, ticketId).getComments();

        assertThat(reloadedComments).extracting(Comment::getId).containsExactlyElementsOf(expectedOrder);
        assertThat(reloadedComments).extracting(Comment::getCreatedAt).isSorted();
        assertThat(reloadedComments).extracting(Comment::getContent).startsWith("Earliest note.").endsWith("Latest note.");
    }

    @Test
    void shouldDeleteACommentThatIsRemovedFromItsTicket() {
        Ticket ticket = newTicket("Badge reader offline", TicketPriority.HIGH, null);
        Comment kept = newComment("alice@example.com", "Still broken this morning.", BASE_TIME);
        Comment removed = newComment("bob@example.com", "Posted against the wrong ticket.", BASE_TIME.plusSeconds(30));
        ticket.addComment(kept);
        ticket.addComment(removed);
        UUID ticketId = entityManager.persistAndGetId(ticket, UUID.class);
        UUID keptId = kept.getId();
        UUID removedId = removed.getId();
        flushAndClear();

        Ticket managed = entityManager.find(Ticket.class, ticketId);
        managed.removeComment(managed.getComments().stream()
                .filter(comment -> comment.getId().equals(removedId))
                .findFirst()
                .orElseThrow());
        flushAndClear();

        assertThat(entityManager.find(Comment.class, removedId)).isNull();
        assertThat(entityManager.find(Comment.class, keptId)).isNotNull();
        assertThat(entityManager.find(Ticket.class, ticketId).getComments())
                .extracting(Comment::getId)
                .containsExactly(keptId);
    }

    @Test
    void shouldDeleteAllCommentsWhenTheirTicketIsDeleted() {
        Ticket ticket = newTicket("Coffee machine leaking", TicketPriority.LOW, null);
        ticket.addComment(newComment("alice@example.com", "Puddle under the counter.", BASE_TIME));
        ticket.addComment(newComment("bob@example.com", "Unplugged it for now.", BASE_TIME.plusSeconds(45)));
        UUID ticketId = entityManager.persistAndGetId(ticket, UUID.class);
        flushAndClear();

        entityManager.remove(entityManager.find(Ticket.class, ticketId));
        flushAndClear();

        assertThat(entityManager.find(Ticket.class, ticketId)).isNull();
        assertThat(countCommentsFor(ticketId)).isZero();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private long countCommentsFor(UUID ticketId) {
        EntityManager delegate = entityManager.getEntityManager();
        return delegate
                .createQuery("select count(c) from Comment c where c.ticket.id = :ticketId", Long.class)
                .setParameter("ticketId", ticketId)
                .getSingleResult();
    }

    private static Ticket newTicket(String title, TicketPriority priority, String assignee) {
        Ticket ticket = new Ticket();
        ticket.setTitle(title);
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setPriority(priority);
        ticket.setAssignee(assignee);
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
