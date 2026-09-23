package com.ticketsystem.ticket.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.lang.Nullable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestContextManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Property-based test for {@link TicketSpecifications} filter semantics against a real database
 * (Req 6.1, 6.2, 6.5, 7.1, 7.2, 7.5).
 *
 * <p>The predicates only exist as Criteria API expressions, so there is nothing meaningful to assert
 * about them in isolation — their behaviour is whatever SQL the dialect produces. This test therefore
 * runs the same {@code @DataJpaTest} slice as the other repository tests (H2 in PostgreSQL mode, with
 * the Flyway V1 migration owning the schema) and compares query results against predicates evaluated
 * in plain Java.
 *
 * <p>jqwik drives the properties rather than JUnit Jupiter, and jqwik does not honour Jupiter
 * extensions, so the Spring slice is prepared through {@link TestContextManager} directly: the
 * {@code @DataJpaTest} bootstrapper builds and caches the same context the Jupiter-based repository
 * tests use, and dependency injection is re-applied for each try because jqwik creates a fresh test
 * instance per try.
 *
 * <p>Spring's transactional rollback is also Jupiter-driven and therefore unavailable here, so each
 * try runs inside a transaction that is marked rollback-only up front. Nothing this test writes is
 * ever committed, which matters because the context — and the H2 database behind it — is shared with
 * every other slice test in the JVM.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TicketFilterCompositionPropertyTest {

    private static final Instant BASE_TIME = Instant.parse("2026-03-01T10:15:30.123456Z");

    /**
     * Search vocabulary. ASCII only and deliberately overlapping ({@code login} is a substring of
     * nothing else, {@code Laptop} and {@code Reboot} share {@code o}s, {@code Wi-Fi} and
     * {@code e-mail} share a hyphen), so generated keywords hit multiple tickets and multiple fields
     * instead of matching at most one row.
     *
     * <p>Mixed case is intentional: the reference predicate lowercases with {@link Locale#ROOT} and the
     * query lowercases in the database, and only ASCII is guaranteed to agree between the two.
     *
     * <p>{@code 100%} and {@code under_score} carry the {@code LIKE} metacharacters. Java's
     * {@code String.contains} treats them literally, so including them here means the reference
     * predicate silently demands that the specification escapes them too.
     */
    private static final List<String> TOKENS =
            List.of(
                    "printer",
                    "Laptop",
                    "VPN",
                    "login",
                    "crash",
                    "Wi-Fi",
                    "e-mail",
                    "100%",
                    "under_score",
                    "timeout",
                    "Reboot",
                    "queue");

    private static TestContextManager testContextManager;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeContainer
    static void prepareSpringSlice() {
        testContextManager = new TestContextManager(TicketFilterCompositionPropertyTest.class);
    }

    @BeforeTry
    void injectSpringDependencies() throws Exception {
        testContextManager.prepareTestInstance(this);
    }

    // Feature: support-ticket-management, Property 18: Filter results equal the reference predicate,
    // and combining filters yields the intersection
    /**
     * Property 18: the keyword filter, the status filter, and their combination all agree with
     * predicates computed independently of the query.
     *
     * <p>For a generated corpus with randomly assigned statuses, three queries are run — keyword only,
     * status only, and both — and each result set is compared for exact set equality against its
     * reference predicate: case-insensitive substring match on title or description (Req 6.1), and
     * exact status equality (Req 7.1). Set equality in both directions is what makes a
     * non-matching keyword or status return the empty set rather than something arbitrary
     * (Req 6.2, 7.2).
     *
     * <p>The combined query is then pinned three ways: it equals the intersection of the two
     * single-filter result sets, it is a subset of each of them, and every ticket it returns carries
     * exactly the requested status (Req 6.5, 7.5). The subset assertions are not implied by the
     * intersection equality alone — they are what rules out a composition that accidentally ORs the
     * predicates and so returns rows outside the requested status.
     *
     * <p>A null status filter is generated as well, standing for the unfiltered case (Req 7.4): the
     * reference predicate then admits every ticket, so the query must return the whole corpus rather
     * than nothing. A status value outside the enum cannot be generated because it is not
     * representable as a {@link TicketStatus}, which is the type-level half of Req 7.5; this property
     * covers the observable half, that no returned ticket ever sits outside the requested filter.
     *
     * <p><strong>Validates: Requirements 6.1, 6.2, 6.5, 7.1, 7.2, 7.5</strong>
     */
    @Property(tries = 100)
    void filterResultsEqualTheReferencePredicateAndCombineAsIntersection(
            @ForAll("corpora") List<TicketSpec> corpus,
            @ForAll("keywords") String keyword,
            @ForAll("statusFilters") @Nullable TicketStatus statusFilter) {

        inRolledBackTransaction(
                () -> {
                    Map<UUID, TicketSpec> persisted = persist(corpus);

                    List<Ticket> keywordOnly =
                            ticketRepository.findAll(TicketSpecifications.matching(keyword, null));
                    List<Ticket> statusOnly =
                            ticketRepository.findAll(TicketSpecifications.matching(null, statusFilter));
                    List<Ticket> combined =
                            ticketRepository.findAll(TicketSpecifications.matching(keyword, statusFilter));

                    Set<UUID> expectedKeyword = idsMatching(persisted, spec -> containsIgnoringCase(spec, keyword));
                    Set<UUID> expectedStatus = idsMatching(persisted, spec -> statusFilter == null || spec.status() == statusFilter);
                    Set<UUID> expectedCombined = intersection(expectedKeyword, expectedStatus);

                    assertThat(idsOf(keywordOnly))
                            .as("keyword-only result for '%s' should equal the reference substring match", keyword)
                            .isEqualTo(expectedKeyword);

                    assertThat(idsOf(statusOnly))
                            .as("status-only result for %s should equal the reference status match", statusFilter)
                            .isEqualTo(expectedStatus);

                    assertThat(idsOf(combined))
                            .as("combined result for ('%s', %s) should equal the intersection", keyword, statusFilter)
                            .isEqualTo(expectedCombined);

                    assertThat(idsOf(keywordOnly))
                            .as("combined result should be a subset of the keyword-only result")
                            .containsAll(idsOf(combined));

                    assertThat(idsOf(statusOnly))
                            .as("combined result should be a subset of the status-only result")
                            .containsAll(idsOf(combined));

                    if (statusFilter != null) {
                        assertThat(combined)
                                .as("no ticket outside status %s may be returned by the combined query", statusFilter)
                                .allSatisfy(ticket -> assertThat(ticket.getStatus()).isEqualTo(statusFilter));
                        assertThat(statusOnly)
                                .as("no ticket outside status %s may be returned by the status-only query", statusFilter)
                                .allSatisfy(ticket -> assertThat(ticket.getStatus()).isEqualTo(statusFilter));
                    }
                });
    }

    /**
     * Runs {@code body} inside a transaction that is rolled back unconditionally, so a try neither
     * sees another try's rows nor leaves any of its own behind for the tests sharing this context.
     *
     * <p>Assertion failures propagate out of the callback; the rollback marker is set before the body
     * runs, so the rollback happens either way.
     */
    private void inRolledBackTransaction(Runnable body) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.execute(
                status -> {
                    status.setRollbackOnly();
                    body.run();
                    return null;
                });
    }

    /**
     * Persists the corpus and returns the generated identifiers mapped to the specs they came from, so
     * the reference predicates can be evaluated over exactly the rows the query could see.
     *
     * <p>Any rows left over from an earlier test are removed first — inside the same rolled-back
     * transaction, so they come back afterwards. Without this, a leaked committed row would make the
     * set equality assertions fail for a reason that has nothing to do with the specifications.
     */
    private Map<UUID, TicketSpec> persist(List<TicketSpec> corpus) {
        ticketRepository.deleteAll();

        List<Ticket> entities = new ArrayList<>(corpus.size());
        for (TicketSpec spec : corpus) {
            entities.add(spec.toEntity());
        }
        List<Ticket> saved = ticketRepository.saveAllAndFlush(entities);

        Map<UUID, TicketSpec> byId = new LinkedHashMap<>();
        for (int i = 0; i < saved.size(); i++) {
            byId.put(saved.get(i).getId(), corpus.get(i));
        }
        return byId;
    }

    /**
     * The reference keyword predicate: a case-insensitive substring match against title or
     * description, evaluated with {@link Locale#ROOT} so it does not depend on the JVM default locale.
     * Written with {@code String.contains} rather than a regex or a pattern, so {@code %} and {@code _}
     * in the keyword are inherently literal.
     */
    private static boolean containsIgnoringCase(TicketSpec spec, String keyword) {
        String needle = keyword.toLowerCase(Locale.ROOT);
        if (spec.title().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        return spec.description() != null && spec.description().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static Set<UUID> idsMatching(Map<UUID, TicketSpec> persisted, SpecPredicate predicate) {
        Set<UUID> matching = new LinkedHashSet<>();
        persisted.forEach(
                (id, spec) -> {
                    if (predicate.test(spec)) {
                        matching.add(id);
                    }
                });
        return matching;
    }

    private static Set<UUID> idsOf(List<Ticket> tickets) {
        Set<UUID> ids = new LinkedHashSet<>();
        tickets.forEach(ticket -> ids.add(ticket.getId()));
        return ids;
    }

    private static Set<UUID> intersection(Set<UUID> left, Set<UUID> right) {
        Set<UUID> both = new LinkedHashSet<>(left);
        both.retainAll(right);
        return both;
    }

    /**
     * Ticket corpora of 0..20 rows. The empty corpus is included so the "matches nothing" case
     * (Req 6.2, 7.2) is reached both through an empty table and through a non-empty table that simply
     * has no match.
     *
     * <p>Statuses are drawn uniformly per ticket, which is what makes combined queries interesting: a
     * corpus where every row shares a status would make the intersection assertion trivially equal to
     * the keyword-only result.
     */
    @Provide
    Arbitrary<List<TicketSpec>> corpora() {
        Arbitrary<String> titles =
                Arbitraries.of(TOKENS).list().ofMinSize(1).ofMaxSize(4).map(words -> String.join(" ", words));
        Arbitrary<String> descriptions =
                Arbitraries.of(TOKENS)
                        .list()
                        .ofMinSize(0)
                        .ofMaxSize(6)
                        .map(words -> String.join(" ", words))
                        .injectNull(0.15);
        Arbitrary<TicketSpec> specs =
                Combinators.combine(
                                titles,
                                descriptions,
                                Arbitraries.of(TicketStatus.class),
                                Arbitraries.of(TicketPriority.class))
                        .as(TicketSpec::new);
        return specs.list().ofMinSize(0).ofMaxSize(20);
    }

    /**
     * Keywords of length 1..200, the range the request boundary admits (Req 6.1).
     *
     * <p>Three shapes in roughly equal measure: a whole vocabulary token (frequent matches), a
     * fragment of one (matches that straddle word boundaries and single-character matches), and random
     * lowercase noise (the empty-result case). Each is then re-cased as-is, lowercased, or uppercased,
     * so a query that only works for the exact stored casing fails.
     */
    @Provide
    Arbitrary<String> keywords() {
        Arbitrary<String> wholeTokens = Arbitraries.of(TOKENS);
        Arbitrary<String> fragments =
                Arbitraries.of(TOKENS)
                        .flatMap(
                                token ->
                                        Arbitraries.integers()
                                                .between(0, token.length() - 1)
                                                .flatMap(
                                                        start ->
                                                                Arbitraries.integers()
                                                                        .between(start + 1, token.length())
                                                                        .map(end -> token.substring(start, end))));
        Arbitrary<String> noise =
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);

        Arbitrary<String> base =
                Arbitraries.frequencyOf(
                        Tuple.of(4, wholeTokens), Tuple.of(4, fragments), Tuple.of(2, noise));

        return Combinators.combine(base, Arbitraries.of(CaseShape.class))
                .as((term, shape) -> shape.apply(term));
    }

    /**
     * Status filters including {@code null}, which stands for "no filter selected" (Req 7.4) rather
     * than for an invalid value — an invalid value is not representable as a {@link TicketStatus}.
     */
    @Provide
    Arbitrary<TicketStatus> statusFilters() {
        return Arbitraries.of(TicketStatus.class).injectNull(0.2);
    }

    private enum CaseShape {
        AS_IS,
        LOWER,
        UPPER;

        String apply(String term) {
            return switch (this) {
                case AS_IS -> term;
                case LOWER -> term.toLowerCase(Locale.ROOT);
                case UPPER -> term.toUpperCase(Locale.ROOT);
            };
        }
    }

    @FunctionalInterface
    private interface SpecPredicate {
        boolean test(TicketSpec spec);
    }

    /**
     * A ticket to be persisted. Kept separate from the entity so the reference predicates read the
     * generated values rather than whatever the entity ended up holding after a round trip.
     */
    record TicketSpec(
            String title, @Nullable String description, TicketStatus status, TicketPriority priority) {

        Ticket toEntity() {
            Ticket ticket = new Ticket();
            ticket.setTitle(title);
            ticket.setDescription(description);
            ticket.setStatus(status);
            ticket.setPriority(priority);
            ticket.setCreatedAt(BASE_TIME);
            ticket.setUpdatedAt(BASE_TIME);
            return ticket;
        }
    }
}
