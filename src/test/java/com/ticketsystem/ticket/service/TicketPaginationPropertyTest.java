package com.ticketsystem.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.ticket.config.UserDirectoryProperties;
import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.mapper.CommentMapper;
import com.ticketsystem.ticket.dto.mapper.TicketMapper;
import com.ticketsystem.ticket.dto.request.TicketQueryParams;
import com.ticketsystem.ticket.dto.response.PagedResponse;
import com.ticketsystem.ticket.dto.response.TicketSummaryResponse;
import com.ticketsystem.ticket.repository.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestContextManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Property-based test for the pagination contract of {@link TicketServiceImpl#list} (Req 2.2, 2.3,
 * 2.5, 9.8).
 *
 * <p>Pagination is not something the service computes on its own: the page window and the count query
 * come from Spring Data and the database, and only {@code totalPages} is derived in Java. Mocking the
 * repository would therefore assert the stub rather than the behaviour, so this test runs the same
 * {@code @DataJpaTest} slice as the repository tests (H2 in PostgreSQL mode, Flyway V1 owning the
 * schema) and drives the real service against a real database.
 *
 * <p>The service's collaborators other than the repository are constructed directly rather than
 * injected: the JPA slice does not contain them, and the mapper, the baseline authorization policy,
 * and the assignee validator are cheap real objects, so using them keeps the assertions about what a
 * caller actually receives.
 *
 * <p>jqwik drives the properties and does not honour Jupiter extensions, so the Spring slice is
 * prepared through {@link TestContextManager} directly and dependency injection is re-applied per try
 * (jqwik builds a fresh test instance each time). Jupiter's transactional rollback is likewise
 * unavailable, so every try runs inside a transaction marked rollback-only up front — nothing is
 * committed, which matters because the context and its H2 database are shared with every other slice
 * test in the JVM.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TicketPaginationPropertyTest {

    private static final Instant BASE_TIME = Instant.parse("2026-04-01T08:00:00Z");

    private static final String ACTOR = "alice";

    /**
     * Upper bound on generated corpus size. Kept at or below {@link TicketQueryParams#MAX_SIZE} so the
     * whole corpus is reachable in a single page, which is what makes the "concatenated pages equal the
     * full result set" assertion possible without re-deriving the sort order in Java.
     */
    private static final int MAX_CORPUS_SIZE = 24;

    private static TestContextManager testContextManager;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TicketService ticketService;

    @BeforeContainer
    static void prepareSpringSlice() {
        testContextManager = new TestContextManager(TicketPaginationPropertyTest.class);
    }

    @BeforeTry
    void injectSpringDependenciesAndBuildService() throws Exception {
        testContextManager.prepareTestInstance(this);

        UserDirectoryProperties directoryProperties = new UserDirectoryProperties();
        directoryProperties.setKnownUsers(Set.of(ACTOR));

        ticketService =
                new TicketServiceImpl(
                        ticketRepository,
                        new TicketMapper(new CommentMapper()),
                        new DefaultTicketAuthorizationService(),
                        new DirectoryBackedAssigneeValidator(
                                new ConfiguredUserDirectory(directoryProperties)),
                        Clock.fixed(BASE_TIME, ZoneOffset.UTC));
    }

    // Feature: support-ticket-management, Property 19: Pagination bounds page size and keeps totals
    // page-independent
    /**
     * Property 19: a page never exceeds the requested size, the totals do not depend on which page was
     * asked for, and the pages partition the result set.
     *
     * <p>For a generated corpus (including the empty one) and a generated {@code (page, size)} pair,
     * five things are asserted:
     *
     * <ul>
     *   <li>{@code content} holds at most {@code size} items — the page size is a bound, not a promise
     *       (Req 2.2).
     *   <li>{@code totalElements} equals the corpus size and is identical across the requested page,
     *       page 0, and the last page, so the total describes the result set rather than the page
     *       (Req 2.3).
     *   <li>{@code totalPages == ceil(totalElements / size)}, checked against a reference computed with
     *       {@code long} arithmetic rather than the service's own integer division (Req 2.3).
     *   <li>A page index at or beyond {@code totalPages} — which for an empty corpus is every page,
     *       including page 0 — returns empty content with the requested {@code page} and {@code size}
     *       echoed back and the totals intact, rather than an error (Req 2.5, 9.8).
     *   <li>Concatenating pages {@code 0..totalPages-1} reproduces the full result set exactly, in
     *       order, with no duplicated and no skipped ticket.
     * </ul>
     *
     * <p>The reference "full result set" is read from the database as a single page rather than sorted
     * in Java on purpose. The ordering contract lives in {@code TicketSpecifications.defaultSort()} and
     * is executed by the database; re-implementing it here would test a second copy of the rule and
     * would make the assertion depend on Java and H2 agreeing on how UUIDs compare. Taken as a single
     * page, it is exactly the sequence the pages must partition.
     *
     * <p>Generated {@code createdAt} values collide often, so most corpora contain timestamp ties. Ties
     * are what break naive pagination: without a total order the database may place one row on two
     * pages and another on none, and the concatenation assertion is what catches that.
     *
     * <p><strong>Validates: Requirements 2.2, 2.3, 2.5, 9.8</strong>
     */
    @Property(tries = 50)
    void paginationBoundsPageSizeAndKeepsTotalsPageIndependent(
            @ForAll("corpora") List<TicketSpec> corpus,
            @ForAll("pageIndexes") int page,
            @ForAll("pageSizes") int size) {

        inRolledBackTransaction(
                () -> {
                    persist(corpus);

                    int expectedTotal = corpus.size();
                    int expectedTotalPages = ceilDiv(expectedTotal, size);

                    PagedResponse<TicketSummaryResponse> requested = list(page, size);

                    assertThat(requested.content())
                            .as("page %s of size %s must hold at most %s items", page, size, size)
                            .hasSizeLessThanOrEqualTo(size);
                    assertThat(requested.page())
                            .as("the requested page index must be echoed back")
                            .isEqualTo(page);
                    assertThat(requested.size())
                            .as("the requested page size must be echoed back")
                            .isEqualTo(size);
                    assertThat(requested.totalElements())
                            .as("totalElements must equal the corpus size, not the page size")
                            .isEqualTo(expectedTotal);
                    assertThat(requested.totalPages())
                            .as("totalPages must equal ceil(%s / %s)", expectedTotal, size)
                            .isEqualTo(expectedTotalPages);

                    // Totals are page-independent: the same query on the first and last page reports
                    // the same result set size and page count as the page actually asked for.
                    PagedResponse<TicketSummaryResponse> firstPage = list(0, size);
                    PagedResponse<TicketSummaryResponse> lastPage =
                            list(Math.max(expectedTotalPages - 1, 0), size);

                    assertThat(List.of(firstPage.totalElements(), lastPage.totalElements()))
                            .as("totalElements must not depend on which page was requested")
                            .containsOnly(requested.totalElements());
                    assertThat(List.of(firstPage.totalPages(), lastPage.totalPages()))
                            .as("totalPages must not depend on which page was requested")
                            .containsOnly(requested.totalPages());

                    // A page at or beyond the end — every page when the corpus is empty (Req 9.8) — is
                    // a successful empty result with the metadata still intact (Req 2.5).
                    if (page >= expectedTotalPages) {
                        assertThat(requested.content())
                                .as("page %s lies at or beyond totalPages %s", page, expectedTotalPages)
                                .isEmpty();
                    }

                    PagedResponse<TicketSummaryResponse> beyondEnd = list(expectedTotalPages, size);
                    assertThat(beyondEnd.content())
                            .as("the page just past the last one must be empty, not an error")
                            .isEmpty();
                    assertThat(beyondEnd.totalElements())
                            .as("metadata past the last page must still describe the whole result set")
                            .isEqualTo(expectedTotal);
                    assertThat(beyondEnd.totalPages()).isEqualTo(expectedTotalPages);

                    assertThatPagesPartitionTheResultSet(size, expectedTotal, expectedTotalPages);
                });
    }

    /**
     * Walks every page of the query and asserts the concatenation is the full result set: same
     * sequence, same length, no repeated identifier.
     *
     * <p>The identifier-set check is not implied by the sequence equality on its own — it is what
     * states plainly that no ticket was served twice across pages, which is the failure mode a
     * duplicated boundary row produces.
     */
    private void assertThatPagesPartitionTheResultSet(int size, int expectedTotal, int totalPages) {
        List<TicketSummaryResponse> fullResultSet = list(0, TicketQueryParams.MAX_SIZE).content();
        assertThat(fullResultSet)
                .as("the reference single-page read must return the whole corpus")
                .hasSize(expectedTotal);

        List<TicketSummaryResponse> concatenated = new ArrayList<>(expectedTotal);
        for (int index = 0; index < totalPages; index++) {
            concatenated.addAll(list(index, size).content());
        }

        assertThat(concatenated)
                .as("pages 0..%s of size %s must concatenate to the full ordered result set",
                        totalPages - 1, size)
                .containsExactlyElementsOf(fullResultSet);

        Set<UUID> distinctIds = new LinkedHashSet<>();
        concatenated.forEach(ticket -> distinctIds.add(ticket.id()));
        assertThat(distinctIds)
                .as("no ticket may appear on more than one page")
                .hasSize(expectedTotal);
    }

    private PagedResponse<TicketSummaryResponse> list(int page, int size) {
        return ticketService.list(new TicketQueryParams(page, size, null, null), ACTOR);
    }

    /** Reference ceiling division in {@code long} arithmetic, independent of the service's own. */
    private static int ceilDiv(long totalElements, int size) {
        return (int) ((totalElements + size - 1) / size);
    }

    /**
     * Runs {@code body} inside a transaction rolled back unconditionally, so a try neither sees
     * another try's rows nor leaves its own behind for the tests sharing this context.
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
     * Persists the corpus, first clearing any rows an earlier test committed — inside the same
     * rolled-back transaction, so they return afterwards. Without this a leaked row would make
     * {@code totalElements} disagree with the corpus size for a reason unrelated to pagination.
     */
    private void persist(List<TicketSpec> corpus) {
        ticketRepository.deleteAll();

        List<Ticket> entities = new ArrayList<>(corpus.size());
        for (TicketSpec spec : corpus) {
            entities.add(spec.toEntity());
        }
        ticketRepository.saveAllAndFlush(entities);
    }

    /**
     * Corpora of 0..{@value #MAX_CORPUS_SIZE} tickets. The empty corpus is generated explicitly rather
     * than left to chance, because it is the case Req 9.8 names: no data ever persisted must still
     * produce valid metadata.
     *
     * <p>{@code createdAt} is drawn from a deliberately narrow band of whole minutes, so timestamp ties
     * are the norm and pagination has to rely on the {@code id} tiebreaker for a total order.
     */
    @Provide
    Arbitrary<List<TicketSpec>> corpora() {
        Arbitrary<TicketSpec> specs =
                Combinators.combine(
                                Arbitraries.integers().between(0, 999),
                                Arbitraries.integers().between(0, 4),
                                Arbitraries.of(TicketStatus.class),
                                Arbitraries.of(TicketPriority.class))
                        .as(
                                (serial, minuteOffset, status, priority) ->
                                        new TicketSpec(
                                                "Ticket " + serial,
                                                BASE_TIME.plusSeconds(minuteOffset * 60L),
                                                status,
                                                priority));

        Arbitrary<List<TicketSpec>> sized = specs.list().ofMinSize(1).ofMaxSize(MAX_CORPUS_SIZE);
        return Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.just(List.<TicketSpec>of())), Tuple.of(9, sized));
    }

    /**
     * Page indexes from 0 up to just past the largest possible page count, so both in-range pages and
     * pages beyond the end are reached (Req 2.5). Page 0 is weighted up because it is the only page
     * that exists for a small corpus.
     */
    @Provide
    Arbitrary<Integer> pageIndexes() {
        return Arbitraries.frequencyOf(
                Tuple.of(3, Arbitraries.just(0)),
                Tuple.of(7, Arbitraries.integers().between(0, MAX_CORPUS_SIZE + 3)));
    }

    /**
     * Page sizes across the whole accepted 1..100 range (Req 2.2).
     *
     * <p>Small sizes are drawn frequently because they are the ones that divide a generated corpus
     * exactly — the boundary where an off-by-one in {@code ceil(totalElements / size)} shows up as a
     * spurious trailing page. Both ends of the range, 1 and 100, are generated explicitly rather than
     * left to a uniform draw to stumble onto.
     */
    @Provide
    Arbitrary<Integer> pageSizes() {
        return Arbitraries.frequencyOf(
                Tuple.of(5, Arbitraries.of(1, 2, 3, 4, 5, 6, 8, 12)),
                Tuple.of(2, Arbitraries.just(TicketQueryParams.DEFAULT_SIZE)),
                Tuple.of(1, Arbitraries.just(TicketQueryParams.MIN_SIZE)),
                Tuple.of(1, Arbitraries.just(TicketQueryParams.MAX_SIZE)),
                Tuple.of(3,
                        Arbitraries.integers()
                                .between(TicketQueryParams.MIN_SIZE, TicketQueryParams.MAX_SIZE)));
    }

    /**
     * A ticket to be persisted. Only the fields pagination and ordering depend on are varied; the
     * description is left null because the list projection drops it.
     */
    record TicketSpec(String title, Instant createdAt, TicketStatus status, TicketPriority priority) {

        Ticket toEntity() {
            Ticket ticket = new Ticket();
            ticket.setTitle(title);
            ticket.setStatus(status);
            ticket.setPriority(priority);
            ticket.setCreatedAt(createdAt);
            ticket.setUpdatedAt(createdAt);
            return ticket;
        }
    }
}
