package com.ticketsystem.ticket.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;

/**
 * Full-stack property-based test for the atomicity of backend validation, driven over real HTTP
 * against the real PostgreSQL engine supplied by {@link AbstractIntegrationTest}.
 *
 * <p>// Feature: support-ticket-management, Property 11: Validation is atomic — a request with any
 * invalid field modifies no record
 *
 * <p><b>Property 11 (Validation is atomic):</b> a create, update, or comment request that contains
 * at least one invalid field is rejected with 400 and leaves <em>every</em> ticket and comment
 * record byte-for-byte unchanged — nothing is created, nothing is modified. This is the fail-closed,
 * atomic-validation guarantee of Requirements 1.3, 4.3, 5.3, 5.4, 5.5, and 10.5: no record is touched
 * until every field of a request has passed validation.
 *
 * <p><b>Validates: Requirements 1.3, 4.3, 5.3, 5.4, 5.5, 10.5</b>
 *
 * <h2>Deep-snapshot strategy</h2>
 *
 * <p>Before each invalid request the test takes a deep snapshot of the whole store — every column of
 * every {@code ticket} row (including {@code version} and {@code updated_at}) and every {@code
 * comment} row — read directly through {@link org.springframework.jdbc.core.JdbcTemplate}. A direct
 * JDBC read runs on a connection outside the request's Hibernate session, so it reflects committed
 * state and cannot be answered from a stale identity map. After the request the snapshot is taken
 * again the same way and asserted equal. Capturing {@code version} and {@code updatedAt} means even a
 * write that "changed nothing observable" but bumped the optimistic-lock version or the timestamp
 * would be caught.
 *
 * <h2>jqwik + Spring lifecycle</h2>
 *
 * <p>jqwik drives its own per-{@code @Property} lifecycle, so JUnit-Jupiter's {@code @BeforeEach}
 * database reset in {@link AbstractIntegrationTest} does not fire per try. Each property therefore
 * resets the store explicitly with {@link #resetDatabase()} at the start of every try before seeding
 * the arbitrary pre-existing contents, then snapshots, sends one invalid request, and re-snapshots.
 * The {@code @SpringBootTest} context (with its autowired {@link TestRestTemplate} and {@code
 * JdbcTemplate}) is shared across the whole {@code PER_CLASS} instance, matching the sibling
 * integration property test {@link AbsentIdentifierPropertyTest}.
 *
 * <h2>Authentication and known assignee</h2>
 *
 * <p>A nested {@link TestSecurityConfig} adds a test-only HTTP Basic chain (ahead of the production
 * default-deny chain) backed by a single in-memory user named {@value #USER}. {@code @TestPropertySource}
 * makes that same name the one configured known assignee, so a valid assignee value exists and an
 * over-long assignee is a genuine 400 field violation rather than a 422.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "ticket.user-directory.known-users=agent")
class ValidationAtomicityPropertyTest extends AbstractIntegrationTest {

    private static final String USER = "agent";
    private static final String PASSWORD = "agent-password";

    @Autowired private TestRestTemplate restTemplate;

    // ---------------------------------------------------------------------------------------------
    // Properties
    // ---------------------------------------------------------------------------------------------

    // Feature: support-ticket-management, Property 11: Validation is atomic — a request with any invalid field modifies no record
    /**
     * A create request carrying at least one invalid field is rejected with 400 and creates nothing,
     * leaving the arbitrary pre-existing store untouched.
     *
     * <p><b>Validates: Requirements 1.3, 10.5</b>
     */
    @Property
    void invalidCreateRequestModifiesNoRecord(
            @ForAll("seedStores") List<SeedTicket> seed,
            @ForAll("invalidCreateBodies") Map<String, Object> body) {

        resetDatabase();
        seedStore(seed);
        StoreSnapshot before = snapshot();

        ResponseEntity<JsonNode> response = post("/api/v1/tickets", body);

        assertThat(response.getStatusCode())
                .as("an invalid create body must be rejected as 400, body=%s", body)
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(snapshot())
                .as("no ticket or comment may be created or modified by a rejected create, body=%s", body)
                .isEqualTo(before);
    }

    // Feature: support-ticket-management, Property 11: Validation is atomic — a request with any invalid field modifies no record
    /**
     * An update request carrying at least one invalid field is rejected with 400 and modifies no
     * ticket — the targeted ticket and every other record are unchanged.
     *
     * <p><b>Validates: Requirements 4.3, 10.5</b>
     */
    @Property
    void invalidUpdateRequestModifiesNoRecord(
            @ForAll("nonEmptySeedStores") List<SeedTicket> seed,
            @ForAll("invalidUpdateBodies") Map<String, Object> partialBody) {

        resetDatabase();
        seedStore(seed);
        StoreSnapshot before = snapshot();

        // Target a real, existing ticket so the request reaches field validation rather than a 404,
        // and supply the ticket's true version so a version mismatch never masks the validation path.
        TicketRow target = before.tickets().get(0);
        Map<String, Object> body = new LinkedHashMap<>(partialBody);
        body.put("version", target.version());

        ResponseEntity<JsonNode> response = patch("/api/v1/tickets/" + target.id(), body);

        assertThat(response.getStatusCode())
                .as("an invalid update body must be rejected as 400, body=%s", body)
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(snapshot())
                .as("no ticket or comment may be modified by a rejected update, body=%s", body)
                .isEqualTo(before);
    }

    // Feature: support-ticket-management, Property 11: Validation is atomic — a request with any invalid field modifies no record
    /**
     * A comment request whose content is invalid is rejected with 400 and creates no comment, leaving
     * the targeted ticket and every record untouched.
     *
     * <p><b>Validates: Requirements 5.3, 5.4, 5.5, 10.5</b>
     */
    @Property
    void invalidCommentRequestModifiesNoRecord(
            @ForAll("nonEmptySeedStores") List<SeedTicket> seed,
            @ForAll("invalidCommentBodies") Map<String, Object> body) {

        resetDatabase();
        seedStore(seed);
        StoreSnapshot before = snapshot();

        UUID targetId = before.tickets().get(0).id();

        ResponseEntity<JsonNode> response = post("/api/v1/tickets/" + targetId + "/comments", body);

        assertThat(response.getStatusCode())
                .as("an invalid comment body must be rejected as 400, body=%s", body)
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(snapshot())
                .as("no comment may be created by a rejected comment request, body=%s", body)
                .isEqualTo(before);
    }

    // ---------------------------------------------------------------------------------------------
    // Generators — arbitrary pre-existing store contents
    // ---------------------------------------------------------------------------------------------

    /** 0..6 pre-existing tickets, each with 0..4 comments; may be empty to cover the empty store. */
    @Provide
    Arbitrary<List<SeedTicket>> seedStores() {
        return seedTicket().list().ofMinSize(0).ofMaxSize(6);
    }

    /** 1..6 pre-existing tickets so update/comment properties always have a real target ticket. */
    @Provide
    Arbitrary<List<SeedTicket>> nonEmptySeedStores() {
        return seedTicket().list().ofMinSize(1).ofMaxSize(6);
    }

    private Arbitrary<SeedTicket> seedTicket() {
        Arbitrary<String> title = validTitle();
        Arbitrary<String> description = Arbitraries.strings().ofMaxLength(80);
        Arbitrary<String> status =
                Arbitraries.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED", "CANCELLED");
        Arbitrary<String> priority = Arbitraries.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
        // Seeded assignee stays null: the test profile's directory only knows "agent", so seeding
        // arbitrary names would need per-row directory setup that adds nothing to the atomicity
        // property. Comments carry realistic non-empty content.
        Arbitrary<List<String>> comments =
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(40).list().ofMaxSize(4);

        return Combinators.combine(title, description, status, priority, comments)
                .as(SeedTicket::new);
    }

    // ---------------------------------------------------------------------------------------------
    // Generators — request bodies with at least one invalid field
    // ---------------------------------------------------------------------------------------------

    /**
     * Create bodies with at least one invalid field, mixing valid and invalid fields so single-field
     * and multi-field failures are both covered. The request is only emitted once at least one field
     * has been forced invalid.
     */
    @Provide
    Arbitrary<Map<String, Object>> invalidCreateBodies() {
        return Combinators.combine(titleField(), descriptionField(), priorityField(), assigneeField())
                .as((titleF, descF, prioF, assigneeF) -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    titleF.applyTo("title", body);
                    descF.applyTo("description", body);
                    prioF.applyTo("priority", body);
                    assigneeF.applyTo("assignee", body);
                    boolean anyInvalid =
                            titleF.invalid || descF.invalid || prioF.invalid || assigneeF.invalid;
                    return new BodyDraft(body, anyInvalid);
                })
                .filter(BodyDraft::anyInvalid)
                .map(BodyDraft::body);
    }

    /**
     * Update bodies (without {@code version}, which the property supplies) with at least one invalid
     * field. Every field is optional on a PATCH, so the draft must have at least one invalid field —
     * which also guarantees at least one field is present.
     */
    @Provide
    Arbitrary<Map<String, Object>> invalidUpdateBodies() {
        return Combinators.combine(
                        optionalTitleField(),
                        optionalDescriptionField(),
                        optionalPriorityField(),
                        optionalAssigneeField())
                .as((titleF, descF, prioF, assigneeF) -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    titleF.applyTo("title", body);
                    descF.applyTo("description", body);
                    prioF.applyTo("priority", body);
                    assigneeF.applyTo("assignee", body);
                    boolean anyInvalid =
                            titleF.invalid || descF.invalid || prioF.invalid || assigneeF.invalid;
                    return new BodyDraft(body, anyInvalid);
                })
                .filter(BodyDraft::anyInvalid)
                .map(BodyDraft::body);
    }

    /** Comment bodies whose content is invalid: absent, explicit null, blank/whitespace, or too long. */
    @Provide
    Arbitrary<Map<String, Object>> invalidCommentBodies() {
        Arbitrary<Map<String, Object>> absent = Arbitraries.just(new LinkedHashMap<>());
        Arbitrary<Map<String, Object>> explicitNull = Arbitraries.just(nullableMap("content", null));
        Arbitrary<Map<String, Object>> blank =
                whitespaceString(0, 40).map(s -> singletonMap("content", s));
        Arbitrary<Map<String, Object>> tooLong =
                Arbitraries.strings().alpha().ofMinLength(5001).ofMaxLength(5050)
                        .map(s -> singletonMap("content", s));
        return Arbitraries.oneOf(absent, explicitNull, blank, tooLong);
    }

    // ---------------------------------------------------------------------------------------------
    // Field-value generators
    // ---------------------------------------------------------------------------------------------

    private Arbitrary<FieldChoice> titleField() {
        Arbitrary<FieldChoice> valid = validTitle().map(FieldChoice::valid);
        return Arbitraries.oneOf(valid, invalidTitleValues());
    }

    private Arbitrary<FieldChoice> descriptionField() {
        Arbitrary<FieldChoice> valid = Arbitraries.strings().ofMaxLength(60).map(FieldChoice::valid);
        Arbitrary<FieldChoice> tooLong =
                Arbitraries.strings().alpha().ofMinLength(5001).ofMaxLength(5050).map(FieldChoice::invalid);
        return Arbitraries.oneOf(valid, tooLong);
    }

    private Arbitrary<FieldChoice> priorityField() {
        Arbitrary<FieldChoice> valid =
                Arbitraries.of("LOW", "MEDIUM", "HIGH", "CRITICAL").map(FieldChoice::valid);
        Arbitrary<FieldChoice> invalid =
                Arbitraries.of("low", "URGENT", "medium ", "NOPE", "Critical").map(FieldChoice::invalid);
        return Arbitraries.oneOf(valid, invalid);
    }

    private Arbitrary<FieldChoice> assigneeField() {
        Arbitrary<FieldChoice> validKnown = Arbitraries.just(FieldChoice.valid(USER));
        Arbitrary<FieldChoice> validNull = Arbitraries.just(FieldChoice.validNull());
        Arbitrary<FieldChoice> tooLong =
                Arbitraries.strings().alpha().ofMinLength(101).ofMaxLength(150).map(FieldChoice::invalid);
        return Arbitraries.oneOf(validKnown, validNull, tooLong);
    }

    // Optional variants (for PATCH): a field may also be omitted entirely.
    private Arbitrary<FieldChoice> optionalTitleField() {
        return Arbitraries.oneOf(Arbitraries.just(FieldChoice.omitted()), titleField());
    }

    private Arbitrary<FieldChoice> optionalDescriptionField() {
        return Arbitraries.oneOf(Arbitraries.just(FieldChoice.omitted()), descriptionField());
    }

    private Arbitrary<FieldChoice> optionalPriorityField() {
        return Arbitraries.oneOf(Arbitraries.just(FieldChoice.omitted()), priorityField());
    }

    private Arbitrary<FieldChoice> optionalAssigneeField() {
        return Arbitraries.oneOf(Arbitraries.just(FieldChoice.omitted()), assigneeField());
    }

    private Arbitrary<FieldChoice> invalidTitleValues() {
        Arbitrary<FieldChoice> blank = whitespaceString(0, 30).map(FieldChoice::invalid);
        Arbitrary<FieldChoice> tooLong =
                Arbitraries.strings().alpha().ofMinLength(201).ofMaxLength(260).map(FieldChoice::invalid);
        Arbitrary<FieldChoice> nullValue = Arbitraries.just(FieldChoice.invalidNull());
        return Arbitraries.oneOf(blank, tooLong, nullValue);
    }

    /** Valid trimmed title, comfortably within 1..200 characters and non-blank after trimming. */
    private Arbitrary<String> validTitle() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(60);
    }

    /** A string composed entirely of whitespace code points drawn from a deliberately wide set. */
    private Arbitrary<String> whitespaceString(int minLen, int maxLen) {
        Arbitrary<Character> ws = Arbitraries.of(
                ' ', '\t', '\n', '\r', '\f', '\u000B', '\u00A0', '\u2007', '\u202F', '\u3000');
        return ws.list()
                .ofMinSize(minLen)
                .ofMaxSize(maxLen)
                .map(chars -> {
                    StringBuilder sb = new StringBuilder();
                    chars.forEach(sb::append);
                    return sb.toString();
                });
    }

    // ---------------------------------------------------------------------------------------------
    // Store seeding and deep-snapshotting (direct JDBC, bypassing any Hibernate session cache)
    // ---------------------------------------------------------------------------------------------

    private void seedStore(List<SeedTicket> seed) {
        for (SeedTicket t : seed) {
            UUID ticketId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO ticket (id, title, description, status, priority, assignee, "
                            + "created_at, updated_at, version) "
                            + "VALUES (?::uuid, ?, ?, ?, ?, NULL, now(), now(), 0)",
                    ticketId.toString(),
                    t.title(),
                    t.description(),
                    t.status(),
                    t.priority());
            for (String content : t.comments()) {
                jdbcTemplate.update(
                        "INSERT INTO comment (id, ticket_id, author, content, created_at) "
                                + "VALUES (?::uuid, ?::uuid, ?, ?, now())",
                        UUID.randomUUID().toString(),
                        ticketId.toString(),
                        USER,
                        content);
            }
        }
    }

    /**
     * Reads every ticket and comment row directly through JDBC, ordered deterministically, capturing
     * every column including {@code version} and {@code updated_at}. Because this is a fresh JDBC read
     * outside any request's persistence context, it reflects committed state only.
     */
    private StoreSnapshot snapshot() {
        List<TicketRow> tickets = new ArrayList<>();
        List<Map<String, Object>> ticketRows = jdbcTemplate.queryForList(
                "SELECT id::text AS id, title, description, status, priority, assignee, "
                        + "created_at::text AS created_at, updated_at::text AS updated_at, version "
                        + "FROM ticket ORDER BY id");
        for (Map<String, Object> r : ticketRows) {
            tickets.add(new TicketRow(
                    UUID.fromString(String.valueOf(r.get("id"))),
                    stringOrNull(r.get("title")),
                    stringOrNull(r.get("description")),
                    stringOrNull(r.get("status")),
                    stringOrNull(r.get("priority")),
                    stringOrNull(r.get("assignee")),
                    stringOrNull(r.get("created_at")),
                    stringOrNull(r.get("updated_at")),
                    ((Number) r.get("version")).longValue()));
        }

        List<String> comments = new ArrayList<>();
        List<Map<String, Object>> commentRows = jdbcTemplate.queryForList(
                "SELECT id::text AS id, ticket_id::text AS ticket_id, author, content, "
                        + "created_at::text AS created_at FROM comment ORDER BY id");
        for (Map<String, Object> r : commentRows) {
            comments.add(r.get("id") + "|" + r.get("ticket_id") + "|" + r.get("author") + "|"
                    + r.get("content") + "|" + r.get("created_at"));
        }
        return new StoreSnapshot(tickets, comments);
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------------------------------------

    private ResponseEntity<JsonNode> post(String path, Map<String, Object> body) {
        return restTemplate.exchange(
                baseUrl() + path, HttpMethod.POST, new HttpEntity<>(body, jsonHeaders()), JsonNode.class);
    }

    private ResponseEntity<JsonNode> patch(String path, Map<String, Object> body) {
        return restTemplate.exchange(
                baseUrl() + path, HttpMethod.PATCH, new HttpEntity<>(body, jsonHeaders()), JsonNode.class);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(USER, PASSWORD);
        return headers;
    }

    private static Map<String, Object> singletonMap(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    /** A map that intentionally carries an explicit null value (a LinkedHashMap permits null values). */
    private static Map<String, Object> nullableMap(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    // ---------------------------------------------------------------------------------------------
    // Value types
    // ---------------------------------------------------------------------------------------------

    /** An arbitrary pre-existing ticket to seed, with its comment contents. */
    record SeedTicket(
            String title, String description, String status, String priority, List<String> comments) {}

    /** A deep, field-level snapshot of the whole store, compared by value equality. */
    record StoreSnapshot(List<TicketRow> tickets, List<String> comments) {}

    /** Every persisted column of a ticket row, including version and updatedAt. */
    record TicketRow(
            UUID id,
            String title,
            String description,
            String status,
            String priority,
            String assignee,
            String createdAt,
            String updatedAt,
            long version) {}

    /** A drafted request body plus whether it contains at least one invalid field. */
    private record BodyDraft(Map<String, Object> body, boolean anyInvalid) {}

    /**
     * One field's contribution to a request body: whether it is omitted, its value, and whether that
     * value is invalid. {@code applyTo} writes it into the body under the given key (or not, if
     * omitted), preserving explicit-null semantics.
     */
    private static final class FieldChoice {
        final boolean omitted;
        final boolean invalid;
        final Object value;

        private FieldChoice(boolean omitted, boolean invalid, Object value) {
            this.omitted = omitted;
            this.invalid = invalid;
            this.value = value;
        }

        static FieldChoice valid(Object value) {
            return new FieldChoice(false, false, value);
        }

        static FieldChoice validNull() {
            return new FieldChoice(false, false, null);
        }

        static FieldChoice invalid(Object value) {
            return new FieldChoice(false, true, value);
        }

        static FieldChoice invalidNull() {
            return new FieldChoice(false, true, null);
        }

        static FieldChoice omitted() {
            return new FieldChoice(true, false, null);
        }

        void applyTo(String key, Map<String, Object> body) {
            if (!omitted) {
                body.put(key, value);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Test-only authentication
    // ---------------------------------------------------------------------------------------------

    /**
     * Test-only security so the property can authenticate over real HTTP, mirroring the pattern used
     * by the other integration tests: production {@code SecurityConfig} is default-deny with no
     * interactive mechanism, so a higher-precedence HTTP Basic chain backed by one in-memory user is
     * added while keeping the same {@code anyRequest().authenticated()} posture. That user's name is
     * also the single configured known assignee (see {@code @TestPropertySource}).
     */
    @TestConfiguration
    static class TestSecurityConfig {

        @Bean
        @Order(0)
        SecurityFilterChain testHttpBasicChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .csrf(AbstractHttpConfigurer::disable)
                    .build();
        }

        @Bean
        @Primary
        UserDetailsService testUserDetailsService() {
            return new InMemoryUserDetailsManager(
                    User.withUsername(USER).password("{noop}" + PASSWORD).authorities("USER").build());
        }
    }
}
