package com.ticketsystem.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketsystem.ticket.config.JacksonConfig;
import com.ticketsystem.ticket.service.CommentService;
import com.ticketsystem.ticket.service.TicketService;
import com.ticketsystem.ticket.service.TicketStatusTransitionService;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * Property-based {@code @WebMvcTest} slice test proving Property 12: an unparseable request body is
 * rejected with 400 Bad Request and never leaks as a 500, for every JSON-accepting write endpoint
 * (Requirement 10.3).
 *
 * <p>The three write endpoints that accept a JSON body are exercised through real HTTP against the
 * two controllers loaded into the slice:
 *
 * <ul>
 *   <li>{@code POST /api/v1/tickets} — create ticket
 *   <li>{@code PATCH /api/v1/tickets/{id}/status} — status transition
 *   <li>{@code POST /api/v1/tickets/{id}/comments} — add comment
 * </ul>
 *
 * <p>Each generated body is malformed in one of the ways Requirement 10.3 and the design call out:
 * truncated objects, mismatched value types, trailing content after a complete value, unbalanced
 * delimiters, or a bare {@code NaN} literal (which strict JSON forbids). Jackson fails to read any of
 * these and raises {@link org.springframework.http.converter.HttpMessageNotReadableException}, which
 * the real {@link GlobalExceptionHandler} maps to 400. The assertion is two-sided: the status must be
 * exactly 400, and it must never be 500 — a 500 would mean a parse error escaped the handler
 * unmapped.
 *
 * <p>Because parsing fails before the controller method body runs, the request never reaches any
 * service. {@code verifyNoInteractions} on all three mocked services is the slice-level proxy for the
 * design's "the store snapshot is unchanged": no service call means no write could have happened.
 *
 * <h2>Why the Spring context is bootstrapped by hand</h2>
 *
 * <p>jqwik runs on its own JUnit Platform engine, so Jupiter's {@code SpringExtension} never processes
 * these methods. Following {@link TicketStatusTransitionPropertyTest}, the class annotations remain
 * the single source of truth for the context; a {@link TestContextManager} built from them injects
 * {@link MockMvc}, the {@link ObjectMapper}, and the mocked services into each per-try instance. The
 * security filter chain is disabled ({@code addFilters = false}) so the slice asserts HTTP semantics
 * only, matching {@link CommentControllerTest}. {@link JacksonConfig} is imported so the slice parses
 * exactly as the running application does.
 */
@WebMvcTest({TicketController.class, CommentController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, JacksonConfig.class})
class UnparseableRequestBodyPropertyTest {

    private static final String CREATE_TICKET_PATH = "/api/v1/tickets";
    private static final String STATUS_PATH = "/api/v1/tickets/{id}/status";
    private static final String COMMENTS_PATH = "/api/v1/tickets/{ticketId}/comments";

    private static TestContextManager contextManager;

    @Autowired private MockMvc mockMvc;

    // Never reached by an unparseable request; asserted via verifyNoInteractions below. Present so the
    // controllers' dependencies are satisfied in the slice.
    @MockBean private TicketService ticketService;
    @MockBean private TicketStatusTransitionService statusTransitionService;
    @MockBean private CommentService commentService;

    @BeforeContainer
    static void createTestContextManager() {
        contextManager = new TestContextManager(UnparseableRequestBodyPropertyTest.class);
    }

    /** Injects the MockMvc and mocked services into this try's instance. */
    @BeforeTry
    void prepareInstance() throws Exception {
        contextManager.prepareTestInstance(this);
    }

    // Feature: support-ticket-management, Property 12: Unparseable request bodies are rejected without
    // side effects.
    /**
     * Property 12: for every malformed JSON body — truncated objects, mismatched types, trailing
     * content, unbalanced delimiters, {@code NaN} literals — sent to any of the three write endpoints,
     * the response is 400 Bad Request, is never 500, and no service is invoked (so nothing could have
     * been written).
     *
     * <p><strong>Validates: Requirements 10.3</strong>
     */
    @Property(tries = 300)
    void unparseableBodyIsAlways400NeverServerError(
            @ForAll("writeEndpoints") WriteEndpoint endpoint,
            @ForAll("malformedJsonBodies") String malformedBody)
            throws Exception {

        UUID resourceId = UUID.randomUUID();

        MvcResult result = mockMvc.perform(endpoint.request(resourceId, malformedBody)).andReturn();

        int statusCode = result.getResponse().getStatus();
        assertThat(statusCode)
                .as("malformed body %s on %s must be a 400 Bad Request",
                        quote(malformedBody), endpoint)
                .isEqualTo(400);
        assertThat(statusCode)
                .as("malformed body %s on %s must never be a 500 (unhandled parse error leaked)",
                        quote(malformedBody), endpoint)
                .isNotEqualTo(500);

        // Parsing failed before any controller method ran, so no service could have written anything.
        verifyNoInteractions(ticketService, statusTransitionService, commentService);
    }

    /**
     * The three JSON-accepting write endpoints, each knowing how to build its own {@link
     * RequestBuilder} from a resource id and a raw body string.
     */
    enum WriteEndpoint {
        CREATE_TICKET {
            @Override
            RequestBuilder request(UUID resourceId, String body) {
                return post(CREATE_TICKET_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body);
            }
        },
        TRANSITION_STATUS {
            @Override
            RequestBuilder request(UUID resourceId, String body) {
                return patch(STATUS_PATH, resourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body);
            }
        },
        ADD_COMMENT {
            @Override
            RequestBuilder request(UUID resourceId, String body) {
                return post(COMMENTS_PATH, resourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body);
            }
        };

        abstract RequestBuilder request(UUID resourceId, String body);
    }

    @Provide
    Arbitrary<WriteEndpoint> writeEndpoints() {
        return Arbitraries.of(WriteEndpoint.class);
    }

    /**
     * Malformed JSON bodies spanning every failure category in Requirement 10.3. A fixed set of
     * hand-picked defects (which must always be covered) is combined with a generative family that
     * mangles otherwise-plausible field content, so the property is not carried by the seeds alone.
     */
    @Provide
    Arbitrary<String> malformedJsonBodies() {
        Arbitrary<String> seeded = Arbitraries.of(
                // Truncated objects.
                "{",
                "{\"title\":",
                "{\"title\":\"hi\"",
                "{\"content\":\"hi\",",
                "{\"status\":\"OPEN\",\"version\":",
                // Mismatched value types (structurally broken, not merely wrong-typed-but-valid).
                "{\"title\":}",
                "{\"version\":true false}",
                "{\"content\":\"a\":\"b\"}",
                // Trailing content after a complete value.
                "{}trailing",
                "{\"title\":\"hi\"} extra",
                "{}{}",
                "{} null",
                // Unbalanced delimiters.
                "}",
                "]",
                "{\"a\":[1,2}",
                "{\"a\":{\"b\":1}",
                "[{\"title\":\"hi\"}",
                // NaN / non-finite literals (invalid in strict JSON).
                "{\"version\":NaN}",
                "{\"version\":Infinity}",
                "{\"version\":-Infinity}",
                "NaN",
                // Bare / empty non-object payloads that cannot bind to a DTO record.
                "",
                "not json at all",
                "'single quoted'");

        Arbitrary<String> generated = malformedFieldValueBodies();

        return Arbitraries.oneOf(seeded, generated);
    }

    /**
     * Generative family: takes an arbitrary field name and a plausible value, then applies one of
     * several structural mutilations (drop the closing brace, add trailing content, unbalance a
     * bracket, splice a {@code NaN} literal) so the body is always broken but in varied, non-seeded
     * ways.
     */
    private Arbitrary<String> malformedFieldValueBodies() {
        Arbitrary<String> fieldNames = Arbitraries.of("title", "content", "status", "version",
                "priority", "description", "assignee");
        Arbitrary<String> values = Arbitraries.of("\"x\"", "123", "true", "null", "\"OPEN\"");
        Arbitrary<Integer> mutation = Arbitraries.integers().between(0, 4);

        return Combinators.combine(fieldNames, values, mutation)
                .as((field, value, mut) -> switch (mut) {
                    // Truncated: no closing brace.
                    case 0 -> "{\"" + field + "\":" + value;
                    // Trailing content after a complete object.
                    case 1 -> "{\"" + field + "\":" + value + "} garbage";
                    // Unbalanced: an extra opening bracket that never closes.
                    case 2 -> "{\"" + field + "\":[" + value + "}";
                    // NaN spliced in as the value (strict JSON rejects it).
                    case 3 -> "{\"" + field + "\":NaN}";
                    // Missing colon between key and value.
                    default -> "{\"" + field + "\" " + value + "}";
                });
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }
}
