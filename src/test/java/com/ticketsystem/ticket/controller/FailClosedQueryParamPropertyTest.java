package com.ticketsystem.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketsystem.ticket.config.JacksonConfig;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.request.TicketQueryParams;
import com.ticketsystem.ticket.dto.response.PagedResponse;
import com.ticketsystem.ticket.dto.response.TicketSummaryResponse;
import com.ticketsystem.ticket.service.TicketService;
import com.ticketsystem.ticket.service.TicketStatusTransitionService;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Property-based {@code @WebMvcTest} slice test proving Property 20: an out-of-range or unrecognized
 * query parameter on {@code GET /api/v1/tickets} is rejected with 400 Bad Request and the response
 * body carries <em>no</em> ticket data — even when the dataset holds tickets that would have matched
 * (Requirements 2.4, 6.4, 6.6, 7.3).
 *
 * <p>The generator covers every out-of-range family the design calls out:
 *
 * <ul>
 *   <li><b>page</b>: negative values including {@link Integer#MIN_VALUE}.
 *   <li><b>size</b>: values {@code <= 0} and {@code > 100}, including {@code 0} and
 *       {@link Integer#MAX_VALUE}.
 *   <li><b>keyword</b>: length {@code 0} (empty) and length {@code > 200}. These are the leak-bait
 *       cases: the empty and over-long keywords are drawn from a "planted corpus" — the mocked
 *       service is stubbed to return a fully populated page for <em>any</em> query, and the over-long
 *       keyword is built by repeating a term that the planted tickets contain. A search-then-validate
 *       implementation that queried first and validated afterwards would therefore return matching
 *       ticket data, and this test would catch the leak.
 *   <li><b>status</b>: strings that are not the exact name of a defined {@link TicketStatus}
 *       (including {@code "open"}, {@code "Open"}, {@code " OPEN"}).
 * </ul>
 *
 * <h2>Why a populated page is stubbed rather than an empty one</h2>
 *
 * <p>At a {@code @WebMvcTest} slice the persistence layer is mocked, so there is no real corpus to
 * plant into. The equivalent is to make {@link TicketService#list} return a non-empty page for every
 * invocation: if the controller (or a hypothetical search-then-validate variant) ever reached the
 * service with a bad parameter, that planted ticket data would appear in the body. Asserting the body
 * contains none of it — no ticket {@code id}, {@code title}, {@code assignee}, no {@code content}
 * array, and either a 400-shaped error or nothing resembling a page — is the slice-level proxy for
 * the design's "the response body contains no ticket data, even when the dataset contains tickets
 * that would have matched".
 *
 * <p>Because {@link TicketQueryParams#status()} is typed as {@link TicketStatus}, an unrecognized
 * status fails during query-parameter conversion and yields a {@code MethodArgumentTypeMismatchException}
 * (400); an out-of-range {@code page}/{@code size} or a 0/over-200 {@code keyword} fails
 * {@code jakarta.validation} on the bound {@link TicketQueryParams} and yields a 400 carrying only
 * field errors. Both paths are validated by the real {@link GlobalExceptionHandler}.
 *
 * <h2>Why the Spring context is bootstrapped by hand</h2>
 *
 * <p>jqwik runs on its own JUnit Platform engine, so Jupiter's {@code SpringExtension} never processes
 * these methods. Following {@link TicketStatusTransitionPropertyTest}, the class annotations remain
 * the single source of truth for the context; a {@link TestContextManager} built from them injects
 * {@link MockMvc}, the {@link ObjectMapper}, and the mocked services into each per-try instance. The
 * security filter chain is disabled ({@code addFilters = false}) so the slice asserts HTTP semantics
 * only. {@link JacksonConfig} is imported so the slice parses exactly as the running application does.
 */
@WebMvcTest(TicketController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, JacksonConfig.class})
class FailClosedQueryParamPropertyTest {

    private static final String LIST_PATH = "/api/v1/tickets";

    /** A term embedded in every planted ticket, so a leaked search would surface it. */
    private static final String PLANTED_TERM = "leakbait";

    /** Field-value tokens from the planted page; none may appear in a 400 response body. */
    private static final String PLANTED_TITLE = "Login fails on SSO " + PLANTED_TERM;
    private static final String PLANTED_ASSIGNEE = "a.patel";
    private static final UUID PLANTED_ID = UUID.fromString("9f1c3b2a-5d4e-4f6a-8b7c-1e2d3f4a5b6c");

    /** Exact defined enum names; any other string is an unrecognized status. */
    private static final Set<String> DEFINED_STATUS_NAMES =
            Arrays.stream(TicketStatus.values()).map(Enum::name).collect(Collectors.toSet());

    private static TestContextManager contextManager;

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    // Stubbed to return a populated "planted" page for ANY query, so a search-then-validate leak would
    // surface ticket data in the body. A correctly fail-closed endpoint never reaches this stub.
    @MockBean private TicketService ticketService;

    // Present so the TicketController's dependencies are satisfied in the slice; never exercised here.
    @MockBean private TicketStatusTransitionService statusTransitionService;

    @BeforeContainer
    static void createTestContextManager() {
        contextManager = new TestContextManager(FailClosedQueryParamPropertyTest.class);
    }

    /** Injects MockMvc, the ObjectMapper, and mocked services, and plants the corpus, per try. */
    @BeforeTry
    void prepareInstance() throws Exception {
        contextManager.prepareTestInstance(this);
        plantCorpus();
    }

    /**
     * Stubs both {@link TicketService#list} overloads to return a fully populated page for any query.
     * This is the slice-level "planted corpus": if a bad parameter ever reached the service, the
     * returned ticket data would appear in the response body and the leak assertions below would fail.
     */
    private void plantCorpus() {
        TicketSummaryResponse planted = new TicketSummaryResponse(
                PLANTED_ID,
                PLANTED_TITLE,
                TicketStatus.OPEN,
                TicketPriority.HIGH,
                PLANTED_ASSIGNEE,
                Instant.parse("2026-09-05T10:15:30Z"),
                Instant.parse("2026-09-05T10:15:30Z"),
                0L);
        PagedResponse<TicketSummaryResponse> page = PagedResponse.of(List.of(planted), 0, 20, 1L);

        given(ticketService.list(any(TicketQueryParams.class), anyString())).willReturn(page);
        given(ticketService.list(any(TicketQueryParams.class), any(), anyString())).willReturn(page);
    }

    // Feature: support-ticket-management, Property 20: Out-of-range and unrecognized query parameters
    // are rejected without returning data.
    /**
     * Property 20: for every out-of-range {@code page}/{@code size}, empty or over-200 {@code keyword}
     * (drawn from the planted corpus), and unrecognized {@code status} value — supplied individually or
     * together — the list request is a 400 and the response body carries no ticket data.
     *
     * <p><strong>Validates: Requirements 2.4, 6.4, 6.6, 7.3</strong>
     */
    @Property(tries = 400)
    void outOfRangeQueryParamsAre400WithNoTicketData(@ForAll("badQueries") BadQuery query)
            throws Exception {

        var request = get(LIST_PATH);
        query.page().ifPresent(p -> request.param("page", p));
        query.size().ifPresent(s -> request.param("size", s));
        query.keyword().ifPresent(k -> request.param("keyword", k));
        query.status().ifPresent(st -> request.param("status", st));

        MvcResult result = mockMvc.perform(request).andReturn();

        int statusCode = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();

        assertThat(statusCode)
                .as("out-of-range query %s must be rejected with 400; body=%s", query, body)
                .isEqualTo(400);

        assertNoTicketDataLeaked(body, query);
    }

    /**
     * Asserts the response body contains none of the planted ticket's field values and no page-shaped
     * ticket collection — the observable form of "no ticket data in the body".
     */
    private void assertNoTicketDataLeaked(String body, BadQuery query) {
        assertThat(body)
                .as("400 for %s must not leak the planted ticket id", query)
                .doesNotContain(PLANTED_ID.toString());
        assertThat(body)
                .as("400 for %s must not leak the planted ticket title", query)
                .doesNotContain(PLANTED_TITLE);
        assertThat(body)
                .as("400 for %s must not leak the planted assignee", query)
                .doesNotContain(PLANTED_ASSIGNEE);
        assertThat(body)
                .as("400 for %s must not leak the planted keyword term (a search leak signature)", query)
                .doesNotContain(PLANTED_TERM);
        assertThat(body)
                .as("400 for %s must not return a page-shaped ticket collection", query)
                .doesNotContain("\"content\"")
                .doesNotContain("\"totalElements\"");
    }

    /**
     * A list request carrying at least one out-of-range or unrecognized parameter. Each field is an
     * {@code Optional<String>} holding the raw query-string value to send, so the family covers a
     * single bad parameter in isolation as well as several at once.
     */
    record BadQuery(
            Optional<String> page,
            Optional<String> size,
            Optional<String> keyword,
            Optional<String> status) {
    }

    /**
     * Generates list queries with at least one out-of-range or unrecognized parameter. Every one of the
     * four dimensions can independently be a valid value or a bad one; the combinator retries until at
     * least one dimension is bad, so no all-valid query (which would be a legitimate 200) is emitted.
     */
    @Provide
    Arbitrary<BadQuery> badQueries() {
        Arbitrary<Optional<String>> pages = Arbitraries.oneOf(
                Arbitraries.just(Optional.empty()),
                Arbitraries.just(Optional.of("0")),
                Arbitraries.just(Optional.of("5")),
                badPages().map(Optional::of));

        Arbitrary<Optional<String>> sizes = Arbitraries.oneOf(
                Arbitraries.just(Optional.empty()),
                Arbitraries.just(Optional.of("1")),
                Arbitraries.just(Optional.of("20")),
                badSizes().map(Optional::of));

        Arbitrary<Optional<String>> keywords = Arbitraries.oneOf(
                Arbitraries.just(Optional.empty()),
                Arbitraries.just(Optional.of(PLANTED_TERM)),
                badKeywords().map(Optional::of));

        Arbitrary<Optional<String>> statuses = Arbitraries.oneOf(
                Arbitraries.just(Optional.empty()),
                Arbitraries.just(Optional.of("OPEN")),
                badStatuses().map(Optional::of));

        return Combinators.combine(pages, sizes, keywords, statuses)
                .as(BadQuery::new)
                .filter(FailClosedQueryParamPropertyTest::hasAtLeastOneBadParameter);
    }

    /** True when the query carries at least one out-of-range or unrecognized value. */
    private static boolean hasAtLeastOneBadParameter(BadQuery q) {
        return q.page().map(FailClosedQueryParamPropertyTest::isBadPage).orElse(false)
                || q.size().map(FailClosedQueryParamPropertyTest::isBadSize).orElse(false)
                || q.keyword().map(FailClosedQueryParamPropertyTest::isBadKeyword).orElse(false)
                || q.status().map(FailClosedQueryParamPropertyTest::isBadStatus).orElse(false);
    }

    private static boolean isBadPage(String raw) {
        try {
            return Integer.parseInt(raw) < 0;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static boolean isBadSize(String raw) {
        try {
            int v = Integer.parseInt(raw);
            return v < TicketQueryParams.MIN_SIZE || v > TicketQueryParams.MAX_SIZE;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static boolean isBadKeyword(String raw) {
        return raw.length() < TicketQueryParams.MIN_KEYWORD_LENGTH
                || raw.length() > TicketQueryParams.MAX_KEYWORD_LENGTH;
    }

    private static boolean isBadStatus(String raw) {
        return !DEFINED_STATUS_NAMES.contains(raw);
    }

    /** Negative page indices, always including {@link Integer#MIN_VALUE}. */
    @Provide
    Arbitrary<String> badPages() {
        Arbitrary<String> extremes = Arbitraries.of(
                String.valueOf(Integer.MIN_VALUE), "-1", "-2", "-100");
        Arbitrary<String> randomNegatives =
                Arbitraries.integers().between(Integer.MIN_VALUE, -1).map(String::valueOf);
        return Arbitraries.oneOf(extremes, randomNegatives);
    }

    /** Sizes {@code <= 0} and {@code > 100}, always including {@code 0} and {@link Integer#MAX_VALUE}. */
    @Provide
    Arbitrary<String> badSizes() {
        Arbitrary<String> extremes = Arbitraries.of(
                "0", "-1", String.valueOf(Integer.MIN_VALUE),
                "101", "1000", String.valueOf(Integer.MAX_VALUE));
        Arbitrary<String> tooSmall =
                Arbitraries.integers().between(Integer.MIN_VALUE, 0).map(String::valueOf);
        Arbitrary<String> tooLarge =
                Arbitraries.integers().between(101, Integer.MAX_VALUE).map(String::valueOf);
        return Arbitraries.oneOf(extremes, tooSmall, tooLarge);
    }

    /**
     * Keywords of length 0 and {@code > 200}, planted so they would match the corpus. The empty
     * keyword is the boundary leak-bait; the over-long keywords embed {@link #PLANTED_TERM} so a
     * search run before validation would return the planted ticket.
     */
    @Provide
    Arbitrary<String> badKeywords() {
        Arbitrary<String> empty = Arbitraries.just("");
        // Over-200 keywords that all contain the planted term, so a leaked search would match.
        Arbitrary<String> overLong = Arbitraries.integers().between(201, 400)
                .map(len -> {
                    StringBuilder sb = new StringBuilder(PLANTED_TERM);
                    while (sb.length() < len) {
                        sb.append('x');
                    }
                    return sb.substring(0, len);
                });
        return Arbitraries.oneOf(empty, overLong);
    }

    /**
     * Status strings that are never the exact name of a defined {@link TicketStatus}, including the
     * casing and whitespace near-misses a lenient binder might coerce.
     */
    @Provide
    Arbitrary<String> badStatuses() {
        Arbitrary<String> seeded = Arbitraries.of(
                "open", "Open", " OPEN", "OPEN ", "in_progress", "Resolved",
                "closed", "cancelled", "DONE", "UNKNOWN", "123");
        Arbitrary<String> random = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_ ")
                .ofMinLength(1)
                .ofMaxLength(20)
                .filter(s -> !DEFINED_STATUS_NAMES.contains(s));
        return Arbitraries.oneOf(seeded, random).filter(s -> !DEFINED_STATUS_NAMES.contains(s));
    }
}
