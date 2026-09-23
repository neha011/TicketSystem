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
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeProperty;
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

/**
 * Full-stack property-based test for <b>Property 13: Absent ticket identifiers are rejected without side
 * effects</b> (Requirements 3.2, 3.3, 4.4, 5.6), driven over real HTTP against the real PostgreSQL engine
 * from {@link AbstractIntegrationTest}.
 *
 * <p>The property: with a fixed, non-trivial store seeded (tickets plus comments), a write request whose
 * only defect is the ticket identifier must fail in a way determined <em>solely</em> by the shape of that
 * identifier, and must leave the entire store byte-for-byte unchanged:
 *
 * <ul>
 *   <li>a well-formed UUID that names no existing ticket &rarr; <b>404</b> (Requirements 4.4, 5.6);
 *   <li>a malformed identifier that is not a valid UUID &rarr; <b>400</b> (Requirement 3.3, via
 *       {@code MethodArgumentTypeMismatchException} on the {@code {id}} path variable);
 *   <li>never a 2xx success, never a 5xx;
 *   <li>the complete store snapshot — every ticket and every comment, all fields including {@code version}
 *       and {@code updated_at} — is identical before and after the request (Requirement 3.2, no side
 *       effects).
 * </ul>
 *
 * <p>The identifier is exercised across all three write endpoints that address a ticket by id: field
 * update ({@code PATCH /api/v1/tickets/{id}}), status transition ({@code PATCH
 * /api/v1/tickets/{id}/status}), and comment creation ({@code POST /api/v1/tickets/{id}/comments}). Each
 * request carries a valid-shaped body, so the identifier is the only possible reason for failure.
 *
 * <h2>Snapshotting through {@code JdbcTemplate}</h2>
 *
 * <p>The before/after snapshot is read straight from the {@code ticket} and {@code comment} tables via
 * {@link org.springframework.jdbc.core.JdbcTemplate}, bypassing any Hibernate session cache, so a change
 * that was written but would have been rolled back cannot hide in an identity map. Rows are ordered by id
 * so the two snapshots compare independently of database row order.
 *
 * <h2>jqwik vs JUnit lifecycle</h2>
 *
 * <p>jqwik runs its own {@code @Property} lifecycle, so the Spring {@code @BeforeEach} database reset does
 * not fire per generated example. The store is therefore seeded once per property method in a
 * {@link BeforeProperty} hook and a baseline snapshot captured; because a correct implementation performs
 * no writes for any absent/malformed identifier, that single seeded store stays valid across every try and
 * the baseline is re-asserted after each request.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AbsentIdentifierPropertyTest extends AbstractIntegrationTest {

    private static final String USER = "agent";
    private static final String PASSWORD = "agent-password";

    @Autowired private TestRestTemplate restTemplate;

    /** The ids seeded into the store, so generated "absent" UUIDs can be checked never to collide. */
    private final List<UUID> seededTicketIds = new ArrayList<>();

    /** Baseline snapshot of the whole store, captured once after seeding. */
    private List<Map<String, Object>> baselineTickets;
    private List<Map<String, Object>> baselineComments;

    /**
     * Test-only security so the property can authenticate over real HTTP, mirroring the pattern used by
     * the other integration tests: production {@code SecurityConfig} is default-deny with no interactive
     * mechanism, so a higher-precedence HTTP Basic chain backed by one in-memory user is added while
     * keeping the same {@code anyRequest().authenticated()} posture.
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

    /**
     * Seeds a fixed, non-trivial store (several tickets, some carrying comments) once for the property and
     * records the baseline snapshot. Runs inside jqwik's property lifecycle, before any example is
     * generated.
     */
    @BeforeProperty
    void seedStore() {
        resetDatabase();
        seededTicketIds.clear();

        seedTicketWithComments("Seed ticket alpha", "OPEN", "MEDIUM", "a.patel", 2);
        seedTicketWithComments("Seed ticket bravo", "IN_PROGRESS", "HIGH", null, 0);
        seedTicketWithComments("Seed ticket charlie", "RESOLVED", "LOW", "b.jones", 3);
        seedTicketWithComments("Seed ticket delta", "CLOSED", "CRITICAL", null, 1);
        seedTicketWithComments("Seed ticket echo", "CANCELLED", "MEDIUM", "c.smith", 0);

        baselineTickets = snapshotTickets();
        baselineComments = snapshotComments();

        // Guard: the seeded store really is non-trivial.
        assertThat(baselineTickets).hasSize(5);
        assertThat(baselineComments).hasSize(6);
    }

    // Feature: support-ticket-management, Property 13: Absent ticket identifiers are rejected without side effects
    /**
     * For every generated identifier — either a well-formed-but-absent UUID or a malformed id string — the
     * update, status-transition, and comment-create endpoints reject it with the shape-determined status
     * (404 for absent, 400 for malformed) and never mutate the store.
     *
     * <p><b>Validates: Requirements 3.2, 3.3, 4.4, 5.6</b>
     */
    @Property
    void absentAndMalformedIdentifiersAreRejectedWithoutSideEffects(
            @ForAll("identifiers") Identifier identifier) {

        String id = identifier.raw();
        HttpStatus expected = identifier.absentButWellFormed() ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;

        // Update — PATCH /api/v1/tickets/{id} with a valid-shaped body.
        assertRejectedWithoutSideEffects(
                HttpMethod.PATCH,
                "/api/v1/tickets/" + id,
                "{\"title\":\"A valid new title\",\"priority\":\"HIGH\",\"version\":0}",
                expected,
                "update");

        // Status transition — PATCH /api/v1/tickets/{id}/status with a valid-shaped body.
        assertRejectedWithoutSideEffects(
                HttpMethod.PATCH,
                "/api/v1/tickets/" + id + "/status",
                "{\"status\":\"IN_PROGRESS\",\"version\":0}",
                expected,
                "status-transition");

        // Comment create — POST /api/v1/tickets/{id}/comments with a valid-shaped body.
        assertRejectedWithoutSideEffects(
                HttpMethod.POST,
                "/api/v1/tickets/" + id + "/comments",
                "{\"content\":\"A perfectly valid comment.\"}",
                expected,
                "comment-create");
    }

    /**
     * Issues one write request with a valid-shaped body and asserts (a) the response status is exactly the
     * shape-determined expectation and is neither a success nor a 5xx, and (b) the complete store snapshot
     * is unchanged from the baseline.
     */
    private void assertRejectedWithoutSideEffects(
            HttpMethod method, String path, String body, HttpStatus expected, String label) {

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                baseUrl() + path, method, new HttpEntity<>(body, jsonHeaders()), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("%s with identifier in path %s must be %s", label, path, expected)
                .isEqualTo(expected);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("%s must never succeed for an absent/malformed identifier", label)
                .isFalse();
        assertThat(response.getStatusCode().is5xxServerError())
                .as("%s must never surface as a server error for an absent/malformed identifier", label)
                .isFalse();

        assertThat(snapshotTickets())
                .as("no ticket may change after a rejected %s (%s)", label, path)
                .isEqualTo(baselineTickets);
        assertThat(snapshotComments())
                .as("no comment may change after a rejected %s (%s)", label, path)
                .isEqualTo(baselineComments);
    }

    // ---------------------------------------------------------------------------------------------
    // Generators
    // ---------------------------------------------------------------------------------------------

    /** A generated request identifier, tagged with whether it is a well-formed-but-absent UUID. */
    record Identifier(String raw, boolean absentButWellFormed) {}

    /**
     * Draws identifiers from two disjoint pools: well-formed random UUIDs guaranteed not to collide with a
     * seeded id (the 404 arm), and strings that are not valid UUID syntax (the 400 arm).
     */
    @Provide
    Arbitrary<Identifier> identifiers() {
        return Arbitraries.oneOf(absentUuids(), malformedIdentifiers());
    }

    /** Well-formed UUIDs that genuinely do not exist in the seeded store. */
    private Arbitrary<Identifier> absentUuids() {
        return Arbitraries.randomValue(random -> UUID.randomUUID())
                .filter(uuid -> !seededTicketIds.contains(uuid))
                .map(uuid -> new Identifier(uuid.toString(), true));
    }

    /**
     * Strings that are not valid UUIDs, so the {@code {id}} path variable fails to bind and the request is
     * a 400 before any lookup happens. Covers a mix of shapes: plainly non-UUID tokens, numbers, UUIDs
     * with a wrong segment length or an illegal character, and an over-long hex-ish blob.
     */
    private Arbitrary<Identifier> malformedIdentifiers() {
        Arbitrary<String> fixed = Arbitraries.of(
                "not-a-uuid",
                "12345",
                "abc",
                "00000000-0000-0000-0000-00000000000", // 31 hex digits: one short
                "00000000-0000-0000-0000-0000000000000", // 33 hex digits: one long
                "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz", // right shape, illegal 'z' characters
                "gggggggg-gggg-gggg-gggg-gggggggggggg",
                "11111111111111111111111111111111", // 32 hex, no dashes
                "----",
                "%20",
                "null");
        // Also generate random non-hyphenated alphanumeric tokens that can never parse as a UUID.
        Arbitrary<String> generated = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(20)
                .filter(AbsentIdentifierPropertyTest::isNotUuid);
        return Arbitraries.oneOf(fixed, generated)
                .filter(AbsentIdentifierPropertyTest::isNotUuid)
                .map(s -> new Identifier(s, false));
    }

    private static boolean isNotUuid(String candidate) {
        try {
            UUID.fromString(candidate);
            return false;
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Seeding and snapshotting
    // ---------------------------------------------------------------------------------------------

    /** Inserts a committed ticket and {@code commentCount} comments straight through the datasource. */
    private void seedTicketWithComments(
            String title, String status, String priority, String assignee, int commentCount) {
        UUID ticketId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO ticket (id, title, description, status, priority, assignee, created_at, "
                        + "updated_at, version) VALUES (?::uuid, ?, ?, ?, ?, ?, now(), now(), ?)",
                ticketId.toString(),
                title,
                "Description for " + title,
                status,
                priority,
                assignee,
                (long) commentCount);
        for (int i = 0; i < commentCount; i++) {
            jdbcTemplate.update(
                    "INSERT INTO comment (id, ticket_id, author, content, created_at) "
                            + "VALUES (?::uuid, ?::uuid, ?, ?, now())",
                    UUID.randomUUID().toString(),
                    ticketId.toString(),
                    "seed-author-" + i,
                    "Seed comment " + i + " on " + title);
        }
        seededTicketIds.add(ticketId);
    }

    /** Reads every ticket row, ordered by id, straight from the database. */
    private List<Map<String, Object>> snapshotTickets() {
        return normalize(jdbcTemplate.queryForList(
                "SELECT id::text AS id, title, description, status, priority, assignee, "
                        + "created_at::text AS created_at, updated_at::text AS updated_at, version "
                        + "FROM ticket ORDER BY id"));
    }

    /** Reads every comment row, ordered by id, straight from the database. */
    private List<Map<String, Object>> snapshotComments() {
        return normalize(jdbcTemplate.queryForList(
                "SELECT id::text AS id, ticket_id::text AS ticket_id, author, content, "
                        + "created_at::text AS created_at FROM comment ORDER BY id"));
    }

    /** Copies each row into a plain {@link LinkedHashMap} so the snapshots compare by value. */
    private static List<Map<String, Object>> normalize(List<Map<String, Object>> rows) {
        List<Map<String, Object>> copy = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            copy.add(new LinkedHashMap<>(row));
        }
        return copy;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(USER, PASSWORD);
        return headers;
    }
}
