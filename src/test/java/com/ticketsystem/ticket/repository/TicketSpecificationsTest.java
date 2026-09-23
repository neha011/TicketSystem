package com.ticketsystem.ticket.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * Named edge cases for {@link TicketSpecifications} executed against a real database (Req 6.1, 6.2,
 * 7.1, 7.2).
 *
 * <p>These complement the property tests: the properties assert case-invariance, filter equality,
 * and composition over generated corpora, while the cases here pin down the specific characters and
 * empty-result situations that are easy to regress. Every one of them depends on database behaviour
 * ({@code LIKE} escaping, string comparison) rather than on Java logic, which is why they run as a
 * {@code @DataJpaTest} slice with the Flyway schema rather than as plain unit tests.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TicketSpecificationsTest {

    private static final Instant BASE_TIME = Instant.parse("2026-03-01T10:15:30.123456Z");

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * A corpus deliberately containing, for each {@code LIKE} metacharacter, both a ticket that
     * carries it literally and a ticket that a broadened (unescaped) pattern would also match. A
     * specification that failed to escape the keyword would return the extra ticket, so the
     * assertions can distinguish "matched literally" from "matched as a wildcard".
     */
    @BeforeEach
    void seedCorpus() {
        ticketRepository.saveAll(List.of(
                ticket("Disk is 90% full", "Root volume alert", TicketStatus.OPEN),
                ticket("Disk is 90 percent full", "No metacharacter here", TicketStatus.OPEN),
                ticket("Column user_id is null", "Schema mismatch", TicketStatus.IN_PROGRESS),
                ticket("Column userXid is null", "Near miss for an underscore wildcard", TicketStatus.IN_PROGRESS),
                ticket("O'Brien cannot log in", "Reported via \"urgent\" channel", TicketStatus.RESOLVED),
                ticket("Path C:\\temp is unwritable", "Backslash in the path", TicketStatus.CLOSED)));
        flushAndClear();
    }

    @Test
    void shouldTreatAPercentSignInTheKeywordAsALiteralCharacter() {
        List<Ticket> matches = ticketRepository.findAll(TicketSpecifications.keywordMatches("90%"));

        assertThat(matches).extracting(Ticket::getTitle).containsExactly("Disk is 90% full");
    }

    @Test
    void shouldTreatAnUnderscoreInTheKeywordAsALiteralCharacter() {
        List<Ticket> matches = ticketRepository.findAll(TicketSpecifications.keywordMatches("user_id"));

        assertThat(matches).extracting(Ticket::getTitle).containsExactly("Column user_id is null");
    }

    @Test
    void shouldNotMatchEveryTicketWhenTheKeywordIsOnlyWildcardCharacters() {
        List<Ticket> matches = ticketRepository.findAll(TicketSpecifications.keywordMatches("%"));

        assertThat(matches).extracting(Ticket::getTitle).containsExactly("Disk is 90% full");
    }

    @Test
    void shouldMatchASingleQuoteInTheKeywordLiterally() {
        List<Ticket> matches = ticketRepository.findAll(TicketSpecifications.keywordMatches("O'Brien"));

        assertThat(matches).extracting(Ticket::getTitle).containsExactly("O'Brien cannot log in");
    }

    @Test
    void shouldMatchADoubleQuoteInTheKeywordLiterally() {
        List<Ticket> matches = ticketRepository.findAll(TicketSpecifications.keywordMatches("\"urgent\""));

        assertThat(matches).extracting(Ticket::getTitle).containsExactly("O'Brien cannot log in");
    }

    @Test
    void shouldMatchABackslashInTheKeywordLiterally() {
        List<Ticket> matches = ticketRepository.findAll(TicketSpecifications.keywordMatches("C:\\temp"));

        assertThat(matches).extracting(Ticket::getTitle).containsExactly("Path C:\\temp is unwritable");
    }

    @Test
    void shouldReturnAnEmptyResultForAKeywordThatMatchesNoTicket() {
        List<Ticket> matches =
                ticketRepository.findAll(TicketSpecifications.keywordMatches("printer jam"));

        assertThat(matches).isEmpty();
    }

    @Test
    void shouldReturnAnEmptyResultForAStatusThatMatchesNoTicket() {
        List<Ticket> matches =
                ticketRepository.findAll(TicketSpecifications.hasStatus(TicketStatus.CANCELLED));

        assertThat(matches).isEmpty();
    }

    @Test
    void shouldReturnOnlyTicketsWhoseStatusMatchesTheFilterExactly() {
        List<Ticket> matches =
                ticketRepository.findAll(TicketSpecifications.hasStatus(TicketStatus.IN_PROGRESS));

        assertThat(matches).hasSize(2);
        assertThat(matches)
                .allSatisfy(found -> assertThat(found.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS));
    }

    /**
     * The status predicate is an equality comparison against a string column, so its case-sensitivity
     * is the database's (Req 7.1). An enum-typed argument cannot carry the wrong case, which is why
     * this is asserted at the SQL level: {@code 'open'} must not match a row stored as {@code 'OPEN'},
     * in contrast to the keyword predicate, which lowercases both operands on purpose.
     */
    @Test
    void shouldCompareStatusValuesCaseSensitively() {
        long lowercaseMatches = countTicketsWithRawStatus("open");
        long uppercaseMatches = countTicketsWithRawStatus("OPEN");

        assertThat(lowercaseMatches).isZero();
        assertThat(uppercaseMatches).isEqualTo(2);
    }

    private long countTicketsWithRawStatus(String rawStatus) {
        Number count = (Number) entityManager
                .getEntityManager()
                .createNativeQuery("select count(*) from ticket where status = :status")
                .setParameter("status", rawStatus)
                .getSingleResult();
        return count.longValue();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static Ticket ticket(String title, String description, TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setTitle(title);
        ticket.setDescription(description);
        ticket.setStatus(status);
        ticket.setPriority(TicketPriority.MEDIUM);
        ticket.setCreatedAt(BASE_TIME);
        ticket.setUpdatedAt(BASE_TIME);
        return ticket;
    }
}
