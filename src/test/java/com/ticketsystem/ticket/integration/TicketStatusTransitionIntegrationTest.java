package com.ticketsystem.ticket.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.request.CreateTicketRequest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Full-stack integration tests for the ticket status state machine (Requirements 8.1–8.6, 8.8),
 * driven over real HTTP against a real PostgreSQL engine (via the {@link AbstractIntegrationTest}
 * Testcontainers harness).
 *
 * <p>The state machine is verified at three levels by design: the pure
 * {@code TicketStatusTransitionValidator} property tests, service-level property tests with a mocked
 * repository, and — here — real HTTP requests landing in the real controller, service, validator,
 * optimistic-locking layer, and PostgreSQL rows. The point of this level is to prove that
 * terminality and the transition table survive the <em>whole</em> stack and the real database, not
 * just the pure validator: a rejected transition must come back as a real {@code 409} from the
 * running server, and the persisted status must be exactly what the table permits after each step.
 *
 * <h2>Persisted-status assertions</h2>
 *
 * <p>After every accepted transition the test re-reads the ticket two ways — through
 * {@code GET /api/v1/tickets/{id}} (the real read path) and directly from the {@code ticket} table
 * via {@link org.springframework.jdbc.core.JdbcTemplate} — and asserts both report the new status.
 * Reading the row directly closes the gap where a handler could return an optimistic in-memory value
 * that was never committed.
 *
 * <h2>Authentication</h2>
 *
 * <p>Production {@code SecurityConfig} wires a default-deny chain but leaves the concrete
 * authentication mechanism to the deployment environment, so it exposes no credential path a test
 * client could use over real HTTP. The nested {@link TestSecurityConfig} adds a test-only filter
 * chain (ordered ahead of the production one) that keeps the same default-deny posture but accepts
 * HTTP Basic for a single in-memory user, so these tests exercise the real controller/service/DB
 * stack as an <em>authenticated</em> caller. The 401/403 arms are covered elsewhere (task 13.9); the
 * concern here is purely the state machine behind an authenticated request.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TicketStatusTransitionIntegrationTest extends AbstractIntegrationTest {

    private static final String USER = "agent";
    private static final String PASSWORD = "agent-password";

    @Autowired private TestRestTemplate restTemplate;

    /**
     * Test-only security so an integration client can authenticate over real HTTP. It preserves the
     * production default-deny rule ({@code anyRequest().authenticated()}) but adds an HTTP Basic
     * mechanism backed by a single in-memory user. Ordered ahead of the production chain so it wins
     * for the API routes under test.
     */
    @TestConfiguration
    static class TestSecurityConfig {

        @Bean
        @Order(0)
        SecurityFilterChain testHttpBasicChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(org.springframework.security.config.Customizer.withDefaults())
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

    // ---------------------------------------------------------------------------------------------
    // Full lifecycle and cancellation paths
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldWalkFullLifecycleOpenInProgressResolvedClosedOverRealHttp() {
        // given
        UUID ticketId = createOpenTicket("Full lifecycle ticket");
        long version = 0L;
        assertPersistedStatus(ticketId, TicketStatus.OPEN);

        // when / then — each accepted step returns 200 and persists the new status
        version = transitionExpectingOk(ticketId, TicketStatus.IN_PROGRESS, version);
        assertPersistedStatus(ticketId, TicketStatus.IN_PROGRESS);

        version = transitionExpectingOk(ticketId, TicketStatus.RESOLVED, version);
        assertPersistedStatus(ticketId, TicketStatus.RESOLVED);

        transitionExpectingOk(ticketId, TicketStatus.CLOSED, version);
        assertPersistedStatus(ticketId, TicketStatus.CLOSED);
    }

    @Test
    void shouldWalkCancellationPathFromOpenOverRealHttp() {
        // given
        UUID ticketId = createOpenTicket("Cancel from open ticket");

        // when
        transitionExpectingOk(ticketId, TicketStatus.CANCELLED, 0L);

        // then
        assertPersistedStatus(ticketId, TicketStatus.CANCELLED);
    }

    @Test
    void shouldWalkCancellationPathFromInProgressOverRealHttp() {
        // given
        UUID ticketId = createOpenTicket("Cancel from in-progress ticket");
        long version = transitionExpectingOk(ticketId, TicketStatus.IN_PROGRESS, 0L);
        assertPersistedStatus(ticketId, TicketStatus.IN_PROGRESS);

        // when
        transitionExpectingOk(ticketId, TicketStatus.CANCELLED, version);

        // then
        assertPersistedStatus(ticketId, TicketStatus.CANCELLED);
    }

    // ---------------------------------------------------------------------------------------------
    // Terminality survives the full stack (CLOSED and CANCELLED are sinks)
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldRejectEveryOutgoingTransitionFromClosedWith409AgainstRealDatabase() {
        // given — drive a ticket all the way to CLOSED
        UUID ticketId = createOpenTicket("Terminal closed ticket");
        long version = transitionExpectingOk(ticketId, TicketStatus.IN_PROGRESS, 0L);
        version = transitionExpectingOk(ticketId, TicketStatus.RESOLVED, version);
        version = transitionExpectingOk(ticketId, TicketStatus.CLOSED, version);

        // when / then — every defined target (including CLOSED itself) is a 409 and nothing is persisted
        for (TicketStatus target : TicketStatus.values()) {
            ResponseEntity<JsonNode> response = transition(ticketId, target.name(), version);

            assertThat(response.getStatusCode())
                    .as("CLOSED -> %s must be a conflict against the real database", target)
                    .isEqualTo(HttpStatus.CONFLICT);
            assertPersistedStatus(ticketId, TicketStatus.CLOSED);
        }
    }

    @Test
    void shouldRejectEveryOutgoingTransitionFromCancelledWith409AgainstRealDatabase() {
        // given
        UUID ticketId = createOpenTicket("Terminal cancelled ticket");
        long version = transitionExpectingOk(ticketId, TicketStatus.CANCELLED, 0L);

        // when / then
        for (TicketStatus target : TicketStatus.values()) {
            ResponseEntity<JsonNode> response = transition(ticketId, target.name(), version);

            assertThat(response.getStatusCode())
                    .as("CANCELLED -> %s must be a conflict against the real database", target)
                    .isEqualTo(HttpStatus.CONFLICT);
            assertPersistedStatus(ticketId, TicketStatus.CANCELLED);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Illegal-but-defined target is 409; undefined target is 400 (Req 8.8) — through the real stack
    // ---------------------------------------------------------------------------------------------

    @Test
    void shouldReturn409ForIllegalButDefinedTargetThroughRealStack() {
        // given — a fresh OPEN ticket; OPEN -> CLOSED is defined but not permitted
        UUID ticketId = createOpenTicket("Illegal defined target ticket");

        // when
        ResponseEntity<JsonNode> response = transition(ticketId, TicketStatus.CLOSED.name(), 0L);

        // then — a defined-but-forbidden target is a conflict, and the 409 names both statuses (Req 8.7)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message").asText())
                .contains("OPEN")
                .contains("CLOSED");
        assertPersistedStatus(ticketId, TicketStatus.OPEN);
    }

    @Test
    void shouldReturn400ForUndefinedTargetStatusThroughRealStack() {
        // given
        UUID ticketId = createOpenTicket("Undefined target ticket");

        // when — a value that is not a defined TicketStatus name never reaches the state machine
        ResponseEntity<JsonNode> response = transition(ticketId, "NOT_A_STATUS", 0L);

        // then — the parse failure is a 400, never a 409, and the ticket is untouched (Req 8.8)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertPersistedStatus(ticketId, TicketStatus.OPEN);
    }

    @Test
    void shouldDistinguishUndefined400FromIllegalDefined409ForTheSameTicket() {
        // given — one ticket, two requests, so the only difference is the target value itself
        UUID ticketId = createOpenTicket("Both failure modes ticket");

        // when
        ResponseEntity<JsonNode> undefinedTarget = transition(ticketId, "closed", 0L); // wrong case = undefined
        ResponseEntity<JsonNode> illegalDefinedTarget = transition(ticketId, TicketStatus.CLOSED.name(), 0L);

        // then — undefined is a 400, illegal-but-defined is a 409; the two modes never collapse together
        assertThat(undefinedTarget.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(illegalDefinedTarget.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertPersistedStatus(ticketId, TicketStatus.OPEN);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Creates an unassigned OPEN ticket over HTTP and returns its id, asserting a 201. */
    private UUID createOpenTicket(String title) {
        CreateTicketRequest request =
                new CreateTicketRequest(title, "Created by integration test", TicketPriority.MEDIUM, null);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                baseUrl() + "/api/v1/tickets",
                HttpMethod.POST,
                new HttpEntity<>(request, jsonHeaders()),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status").asText()).isEqualTo("OPEN");
        return UUID.fromString(response.getBody().get("id").asText());
    }

    /**
     * Issues a status transition and asserts a 200, returning the new version so the caller can chain
     * the next step with a fresh optimistic-locking value.
     */
    private long transitionExpectingOk(UUID ticketId, TicketStatus target, long version) {
        ResponseEntity<JsonNode> response = transition(ticketId, target.name(), version);

        assertThat(response.getStatusCode())
                .as("transition to %s should succeed", target)
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status").asText()).isEqualTo(target.name());
        return response.getBody().get("version").asLong();
    }

    /**
     * Sends {@code PATCH /api/v1/tickets/{id}/status} with the given raw status string and version,
     * returning the raw response so callers can assert on any status code (200/400/409). The status
     * is sent as a raw string rather than the enum so undefined values can be exercised too.
     */
    private ResponseEntity<JsonNode> transition(UUID ticketId, String rawStatus, long version) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("status", rawStatus);
        body.put("version", version);

        return restTemplate.exchange(
                baseUrl() + "/api/v1/tickets/" + ticketId + "/status",
                HttpMethod.PATCH,
                new HttpEntity<>(body, jsonHeaders()),
                JsonNode.class);
    }

    /** Asserts the persisted status both through the HTTP read path and directly against the row. */
    private void assertPersistedStatus(UUID ticketId, TicketStatus expected) {
        // Direct read from the real ticket table — proves the value was actually committed.
        String rowStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM ticket WHERE id = ?", String.class, ticketId);
        assertThat(rowStatus)
                .as("persisted row status for %s", ticketId)
                .isEqualTo(expected.name());

        // Read back through the real GET endpoint too, so the API surface agrees with the row.
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                baseUrl() + "/api/v1/tickets/" + ticketId,
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status").asText()).isEqualTo(expected.name());
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(USER, PASSWORD);
        return headers;
    }
}
