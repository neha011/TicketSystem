package com.ticketsystem.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketsystem.ticket.config.JacksonConfig;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.service.TicketService;
import com.ticketsystem.ticket.service.TicketStatusTransitionService;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Property-based {@code @WebMvcTest} slice test for the status-transition endpoint, proving Property 7:
 * an <em>undefined</em> target status is a validation failure (400), never a conflict (409), and the
 * two failure modes of Requirement 8.8 never collapse into one.
 *
 * <p>Because {@link com.ticketsystem.ticket.dto.request.StatusTransitionRequest#status()} is typed as
 * {@link TicketStatus} and Jackson is configured to reject unknown enum names (task 6.7), any status
 * string that is not <em>exactly</em> one of {@code OPEN}, {@code IN_PROGRESS}, {@code RESOLVED},
 * {@code CLOSED}, {@code CANCELLED} fails while the body is being parsed. That raises a
 * {@code HttpMessageNotReadableException}, which the real {@link GlobalExceptionHandler} maps to 400 —
 * so the request can never reach {@link TicketStatusTransitionService}, and the ticket's status is
 * therefore left unchanged.
 *
 * <p>The generator draws arbitrary strings, filtered to exclude the five exact enum names, and always
 * includes the seeded near-miss cases {@code "open"}, {@code "Open"}, and {@code " OPEN"} — casings and
 * whitespace variants that a lenient parser might otherwise coerce. For every one, the response must be
 * 400, must never be 409, and the transition service must not be invoked at all
 * ({@code verifyNoInteractions}), which is the observable proxy for "the ticket's status is unchanged"
 * at the controller slice.
 *
 * <h2>Why the Spring context is bootstrapped by hand</h2>
 *
 * <p>jqwik runs on its own JUnit Platform engine, so Jupiter's {@code SpringExtension} — which is what
 * normally acts on {@code @WebMvcTest} — never sees these methods. Following the same pattern as
 * {@link com.ticketsystem.ticket.repository.TicketKeywordSearchPropertyTest}, the class annotations
 * remain the single source of truth for the context configuration; a {@link TestContextManager} is
 * built from them and asked to inject {@link MockMvc}, the {@link ObjectMapper}, and the mocked
 * services into each per-try instance. The security filter chain is disabled ({@code addFilters =
 * false}) so the slice asserts HTTP semantics only, matching {@link CommentControllerTest}.
 *
 * <p>{@link JacksonConfig} is imported explicitly. A {@code @WebMvcTest} slice does not pick up an
 * application {@code @Configuration} automatically, so without this import the slice would parse with
 * a default {@link ObjectMapper} that lacks the {@code WhitespaceIntolerantEnumDeserializer} — and
 * Jackson's built-in enum deserializer trims before matching, so {@code " OPEN"} would bind to
 * {@code OPEN} and the request would wrongly succeed. Importing the real config makes the slice parse
 * exactly as the running application does, which is the behaviour Requirement 8.8 concerns.
 */
@WebMvcTest(TicketController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, JacksonConfig.class})
class TicketStatusTransitionPropertyTest {

    private static final String STATUS_PATH = "/api/v1/tickets/{id}/status";

    /** Exact defined enum names; any other string is an undefined target. */
    private static final Set<String> DEFINED_STATUS_NAMES =
            Arrays.stream(TicketStatus.values()).map(Enum::name).collect(Collectors.toSet());

    private static TestContextManager contextManager;

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    // Not touched by any undefined-status request; asserted via verifyNoInteractions below.
    @MockBean private TicketStatusTransitionService statusTransitionService;

    // Present so the TicketController's dependencies are satisfied in the slice; never exercised here.
    @MockBean private TicketService ticketService;

    @BeforeContainer
    static void createTestContextManager() {
        contextManager = new TestContextManager(TicketStatusTransitionPropertyTest.class);
    }

    /** Injects the MockMvc, ObjectMapper, and mocked services into this try's instance. */
    @BeforeTry
    void prepareInstance() throws Exception {
        contextManager.prepareTestInstance(this);
    }

    // Feature: support-ticket-management, Property 7: Undefined target status values are rejected as
    // validation failures, never as conflicts.
    /**
     * Property 7: for any status string that is not an exact {@link TicketStatus} name — including the
     * casing and whitespace near-misses {@code "open"}, {@code "Open"}, {@code " OPEN"} — a status
     * transition request is rejected with 400, is never 409, and never reaches the transition service,
     * so the ticket's status is unchanged.
     *
     * <p><strong>Validates: Requirements 8.8</strong>
     */
    @Property(tries = 300)
    void undefinedTargetStatusIsAlways400NeverConflict(
            @ForAll("undefinedStatusStrings") String undefinedStatus,
            @ForAll("versions") long version)
            throws Exception {

        UUID ticketId = UUID.randomUUID();
        String body = rawTransitionBody(undefinedStatus, version);

        MvcResult result =
                mockMvc.perform(patch(STATUS_PATH, ticketId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn();

        int statusCode = result.getResponse().getStatus();
        assertThat(statusCode)
                .as("undefined target status %s must be a 400 validation failure", quote(undefinedStatus))
                .isEqualTo(400);
        assertThat(statusCode)
                .as("undefined target status %s must never be a 409 conflict", quote(undefinedStatus))
                .isNotEqualTo(409);

        // The request never reached the transition service, so no ticket status was mutated.
        verifyNoInteractions(statusTransitionService);
    }

    /** Builds the raw JSON body directly so a non-enum status string is sent verbatim to the parser. */
    private String rawTransitionBody(String status, long version) throws Exception {
        // objectMapper.writeValueAsString handles escaping of quotes, backslashes, and control chars
        // in the generated status string, so the body is always syntactically valid JSON whose only
        // defect is the undefined status value.
        String encodedStatus = objectMapper.writeValueAsString(status);
        return "{\"status\":" + encodedStatus + ",\"version\":" + version + "}";
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    /**
     * Arbitrary strings that are never an exact {@link TicketStatus} name, plus the always-included
     * near-miss seeds {@code "open"}, {@code "Open"}, {@code " OPEN"}. The random arm draws
     * mixed-content strings (letters, digits, underscores, spaces) and filters out anything that
     * happens to equal a defined name, so casing and whitespace variants of real names are exercised
     * rather than excluded.
     */
    @Provide
    Arbitrary<String> undefinedStatusStrings() {
        Arbitrary<String> seeded = Arbitraries.of("open", "Open", " OPEN", "OPEN ", "in_progress",
                "Resolved", "closed", "cancelled", "DONE", "", "  ", "12345", "OPEN\t");

        Arbitrary<String> randomStrings =
                Arbitraries.strings()
                        .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_ 0123456789")
                        .ofMinLength(0)
                        .ofMaxLength(30)
                        .filter(s -> !DEFINED_STATUS_NAMES.contains(s));

        return Arbitraries.oneOf(seeded, randomStrings)
                .filter(s -> !DEFINED_STATUS_NAMES.contains(s));
    }

    /** A spread of version values, none of which should matter for a 400. */
    @Provide
    Arbitrary<Long> versions() {
        return Arbitraries.longs().between(0L, 50L);
    }
}
