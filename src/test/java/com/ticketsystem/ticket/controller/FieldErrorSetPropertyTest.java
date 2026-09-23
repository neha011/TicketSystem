package com.ticketsystem.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketsystem.ticket.config.JacksonConfig;
import com.ticketsystem.ticket.service.CommentService;
import com.ticketsystem.ticket.service.TicketService;
import com.ticketsystem.ticket.service.TicketStatusTransitionService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
 * Property-based {@code @WebMvcTest} slice test proving Property 10: when a create-ticket,
 * update-ticket, or create-comment request carries validation failures, the {@code fieldErrors}
 * collection in the 400 response names <em>exactly</em> the invalid fields — no invalid field
 * omitted, no valid field reported — and every entry carries a non-empty reason.
 *
 * <p>The test generates a <em>validity vector</em> over {@code (title, description, priority,
 * assignee)}, marking each field independently valid or invalid, and drives it through the three
 * write endpoints that expand {@code @Valid} body failures into {@code fieldErrors} via the real
 * {@link GlobalExceptionHandler}. The comment request has a single field, so its vector collapses to
 * {@code content}. Runs are forced with two, three, and four simultaneously invalid fields so the
 * completeness claim is exercised under multiple concurrent failures rather than only one.
 *
 * <p>Exact-set equality between the reported field names and the expected failing set is a stronger
 * assertion than the acceptance criteria require individually: it establishes per-field independence,
 * completeness under several concurrent failures, and the absence of spurious errors, all at once.
 *
 * <h2>Why the Spring context is bootstrapped by hand</h2>
 *
 * <p>jqwik runs on its own JUnit Platform engine, so Jupiter's {@code SpringExtension} — which is what
 * normally acts on {@code @WebMvcTest} — never sees these methods. Following the same pattern as
 * {@link TicketStatusTransitionPropertyTest}, the class annotations remain the single source of truth
 * for the context configuration; a {@link TestContextManager} is built from them and asked to inject
 * {@link MockMvc}, the {@link ObjectMapper}, and the mocked services into each per-try instance. The
 * security filter chain is disabled ({@code addFilters = false}) so the slice asserts validation
 * semantics only.
 *
 * <p>{@link JacksonConfig} is imported explicitly so the slice parses request bodies exactly as the
 * running application does — the same reason {@link TicketStatusTransitionPropertyTest} imports it.
 * Because every generated request has at least two invalid fields, each one is a 400 and no request
 * ever reaches the mocked services; the mocks exist only to satisfy the controllers' constructors.
 */
@WebMvcTest(controllers = {TicketController.class, CommentController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, JacksonConfig.class})
class FieldErrorSetPropertyTest {

    private static final String CREATE_PATH = "/api/v1/tickets";
    private static final String UPDATE_PATH = "/api/v1/tickets/{id}";
    private static final String COMMENT_PATH = "/api/v1/tickets/{id}/comments";

    /** A trimmed length that satisfies both the 200-char title and 5000-char description/content rules. */
    private static final String VALID_TEXT = "Login fails on SSO";

    private static TestContextManager contextManager;

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    // None of these are exercised: every generated request has >= 2 invalid fields and so is a 400
    // before any service call. They exist only to satisfy the controllers' constructors in the slice.
    @MockBean private TicketService ticketService;
    @MockBean private TicketStatusTransitionService statusTransitionService;
    @MockBean private CommentService commentService;

    @BeforeContainer
    static void createTestContextManager() {
        contextManager = new TestContextManager(FieldErrorSetPropertyTest.class);
    }

    /** Injects the MockMvc, ObjectMapper, and mocked services into this try's instance. */
    @BeforeTry
    void prepareInstance() throws Exception {
        contextManager.prepareTestInstance(this);
    }

    // Feature: support-ticket-management, Property 10: Reported field errors are exactly the set of
    // invalid fields.
    /**
     * Property 10 for create-ticket: a {@code POST /api/v1/tickets} body with a generated validity
     * vector over {@code (title, description, priority, assignee)} — forced to have two, three, or four
     * invalid fields — is a 400 whose {@code fieldErrors} field names equal exactly the invalid set,
     * each with a non-empty reason.
     *
     * <p><strong>Validates: Requirements 1.4, 1.5, 4.3, 4.9, 4.10, 10.1, 10.2</strong>
     */
    @Property(tries = 200)
    void createFieldErrorsEqualTheInvalidFieldSet(
            @ForAll("validityVectors") boolean[] validity) throws Exception {

        Set<String> expectedInvalid = new LinkedHashSet<>();
        String body = buildCreateBody(validity, expectedInvalid);

        MvcResult result =
                mockMvc.perform(post(CREATE_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn();

        assertFieldErrorsEqual(result, expectedInvalid, "create", body);
    }

    // Feature: support-ticket-management, Property 10: Reported field errors are exactly the set of
    // invalid fields.
    /**
     * Property 10 for update-ticket: a {@code PATCH /api/v1/tickets/{id}} body whose four patchable
     * fields are all present (so all are validated) with a generated validity vector — forced to have
     * two, three, or four invalid fields — is a 400 whose {@code fieldErrors} field names equal exactly
     * the invalid set, each with a non-empty reason. The required {@code version} is always valid, so it
     * never appears in the expected set.
     *
     * <p><strong>Validates: Requirements 1.4, 1.5, 4.3, 4.9, 4.10, 10.1, 10.2</strong>
     */
    @Property(tries = 200)
    void updateFieldErrorsEqualTheInvalidFieldSet(
            @ForAll("validityVectors") boolean[] validity) throws Exception {

        Set<String> expectedInvalid = new LinkedHashSet<>();
        String body = buildUpdateBody(validity, expectedInvalid);

        MvcResult result =
                mockMvc.perform(patch(UPDATE_PATH, UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn();

        assertFieldErrorsEqual(result, expectedInvalid, "update", body);
    }

    // Feature: support-ticket-management, Property 10: Reported field errors are exactly the set of
    // invalid fields.
    /**
     * Property 10 for create-comment: the comment body has a single validated field, {@code content},
     * so its validity vector collapses to that one field. When it is invalid (blank, over-long, or
     * null) the response is a 400 whose {@code fieldErrors} names exactly {@code content} with a
     * non-empty reason; when it is valid the mocked service would handle it, so this run asserts only
     * the invalid case, which is where Property 10's set-equality claim is meaningful.
     *
     * <p><strong>Validates: Requirements 1.4, 4.9, 4.10, 10.1, 10.2</strong>
     */
    @Property(tries = 100)
    void commentFieldErrorsEqualTheInvalidFieldSet(
            @ForAll("invalidContentValues") String invalidContent) throws Exception {

        String body = invalidContent == null
                ? "{}"
                : "{\"content\":" + objectMapper.writeValueAsString(invalidContent) + "}";

        MvcResult result =
                mockMvc.perform(post(COMMENT_PATH, UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn();

        assertFieldErrorsEqual(result, Set.of("content"), "comment", body);
    }

    /**
     * Asserts the shared Property 10 contract: 400 status, {@code fieldErrors} field names equal to the
     * expected invalid set (no omissions, no spurious entries), and a non-empty reason on every entry.
     */
    private void assertFieldErrorsEqual(
            MvcResult result, Set<String> expectedInvalid, String kind, String body) throws Exception {

        int statusCode = result.getResponse().getStatus();
        String responseBody = result.getResponse().getContentAsString();

        assertThat(statusCode)
                .as("%s request with invalid fields %s must be a 400; body=%s response=%s",
                        kind, expectedInvalid, body, responseBody)
                .isEqualTo(400);

        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode fieldErrors = root.get("fieldErrors");
        assertThat(fieldErrors)
                .as("%s response must carry a fieldErrors array; response=%s", kind, responseBody)
                .isNotNull();
        assertThat(fieldErrors.isArray())
                .as("fieldErrors must be a JSON array; response=%s", responseBody)
                .isTrue();

        Set<String> reportedFields = new LinkedHashSet<>();
        for (JsonNode entry : fieldErrors) {
            JsonNode fieldNode = entry.get("field");
            JsonNode reasonNode = entry.get("reason");

            assertThat(fieldNode)
                    .as("every fieldErrors entry must name a field; response=%s", responseBody)
                    .isNotNull();
            reportedFields.add(fieldNode.asText());

            assertThat(reasonNode)
                    .as("every fieldErrors entry must carry a reason; response=%s", responseBody)
                    .isNotNull();
            assertThat(reasonNode.asText())
                    .as("every fieldErrors reason must be non-empty; response=%s", responseBody)
                    .isNotBlank();
        }

        assertThat(reportedFields)
                .as("reported field errors must equal exactly the invalid field set for the %s request; "
                        + "body=%s response=%s", kind, body, responseBody)
                .isEqualTo(expectedInvalid);
    }

    /**
     * Builds a create-ticket JSON body from the validity vector, recording which fields are invalid in
     * {@code expectedInvalid}. An invalid title/description/assignee overshoots its trimmed-length
     * bound; an invalid priority is an undefined enum name that {@code @NotNull}'s partner constraint
     * would never accept — but since an undefined enum in the body is a parse failure, priority
     * invalidity is instead expressed as an explicit {@code null}, which fails {@code @NotNull} and is
     * reported against the {@code priority} field.
     */
    private String buildCreateBody(boolean[] validity, Set<String> expectedInvalid)
            throws Exception {
        List<String> members = new ArrayList<>();

        members.add("\"title\":" + titleValue(validity[0], expectedInvalid));
        members.add("\"description\":" + descriptionValue(validity[1], expectedInvalid));
        members.add("\"priority\":" + priorityValue(validity[2], expectedInvalid));
        members.add("\"assignee\":" + assigneeValue(validity[3], expectedInvalid));

        return "{" + String.join(",", members) + "}";
    }

    /**
     * Builds an update-ticket JSON body. Every patchable field is present so all four are validated,
     * mirroring the create vector, and a valid {@code version} is always included so it never enters
     * the expected invalid set. An explicit {@code null} priority is the invalid form here too, since
     * {@code priority} carries {@code @NotNull} in the update DTO.
     */
    private String buildUpdateBody(boolean[] validity, Set<String> expectedInvalid)
            throws Exception {
        List<String> members = new ArrayList<>();

        members.add("\"title\":" + titleValue(validity[0], expectedInvalid));
        members.add("\"description\":" + descriptionValue(validity[1], expectedInvalid));
        members.add("\"priority\":" + priorityValue(validity[2], expectedInvalid));
        members.add("\"assignee\":" + assigneeValue(validity[3], expectedInvalid));
        members.add("\"version\":3");

        return "{" + String.join(",", members) + "}";
    }

    private String titleValue(boolean valid, Set<String> expectedInvalid) throws Exception {
        if (valid) {
            return objectMapper.writeValueAsString(VALID_TEXT);
        }
        expectedInvalid.add("title");
        // 201 trimmed characters overshoots the 1..200 trimmed bound.
        return objectMapper.writeValueAsString("t".repeat(201));
    }

    private String descriptionValue(boolean valid, Set<String> expectedInvalid) throws Exception {
        if (valid) {
            return objectMapper.writeValueAsString("A short description.");
        }
        expectedInvalid.add("description");
        // 5001 characters overshoots the @Size(max = 5000) bound.
        return objectMapper.writeValueAsString("d".repeat(5001));
    }

    private String priorityValue(boolean valid, Set<String> expectedInvalid) {
        if (valid) {
            return "\"HIGH\"";
        }
        expectedInvalid.add("priority");
        // Explicit null fails @NotNull and is reported against "priority"; an undefined enum *name*
        // would instead be a parse failure with no per-field entry, which is a different property.
        return "null";
    }

    private String assigneeValue(boolean valid, Set<String> expectedInvalid) throws Exception {
        if (valid) {
            return objectMapper.writeValueAsString("a.patel");
        }
        expectedInvalid.add("assignee");
        // 101 characters overshoots the @Size(max = 100) bound.
        return objectMapper.writeValueAsString("a".repeat(101));
    }

    /**
     * Validity vectors over {@code (title, description, priority, assignee)} forced to have two, three,
     * or four invalid fields (a {@code false} marks an invalid field). Restricting to >= 2 invalid
     * fields is what the task's "two, three, and four simultaneously invalid fields" calls for and keeps
     * every generated request a guaranteed 400 that never reaches a service.
     */
    @Provide
    Arbitrary<boolean[]> validityVectors() {
        // Number of invalid fields: 2, 3, or 4.
        Arbitrary<Integer> invalidCount = Arbitraries.integers().between(2, 4);
        return invalidCount.flatMap(count ->
                shuffledFlags(count).map(flags -> {
                    boolean[] validity = new boolean[4];
                    for (int i = 0; i < 4; i++) {
                        // true == valid; a position flagged invalid becomes false.
                        validity[i] = !flags.contains(i);
                    }
                    return validity;
                }));
    }

    /** Chooses which {@code count} of the four field positions (0..3) are the invalid ones. */
    private Arbitrary<Set<Integer>> shuffledFlags(int count) {
        return Arbitraries.integers().between(0, 3).set().ofSize(count);
    }

    /** Invalid comment content forms: blank (whitespace-only), over-long, and absent (null). */
    @Provide
    Arbitrary<String> invalidContentValues() {
        Arbitrary<String> blanks = Arbitraries.of(" ", "\t", "\n", "   ", "\u00A0", "\u3000");
        Arbitrary<String> overLong = Arbitraries.just("c".repeat(5001));
        Arbitrary<String> absent = Arbitraries.just((String) null);
        return Arbitraries.oneOf(blanks, overLong, absent);
    }
}
