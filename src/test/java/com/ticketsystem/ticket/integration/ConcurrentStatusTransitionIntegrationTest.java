package com.ticketsystem.ticket.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.request.CreateTicketRequest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Full-stack concurrency integration test for the status-transition endpoint (Requirements 4.6 and
 * 8.9), driven over real HTTP against the real PostgreSQL engine supplied by the
 * {@link AbstractIntegrationTest} Testcontainers harness.
 *
 * <p>Req 8.9 requires that when several {@code Status_Transition} requests for the same ticket race,
 * optimistic concurrency control lets <em>at most one</em> win and every loser comes back as a
 * {@code 409 Conflict}. Req 4.6 is the same guarantee expressed as version matching: a request whose
 * supplied {@code version} no longer matches the stored one is rejected with a {@code 409} and
 * leaves the ticket untouched. The pure/service-level layers can only assert this against a mocked
 * repository; the point of this level is to prove the {@code @Version} column, the real
 * {@code ObjectOptimisticLockingFailureException} mapping, and PostgreSQL's row versioning together
 * produce exactly one winner under a genuine thread race.
 *
 * <h2>Race design</h2>
 *
 * <p>One OPEN ticket is created, then {@value #THREAD_COUNT} threads each attempt the <em>same</em>
 * permitted transition (OPEN → IN_PROGRESS) with the same stale {@code version} of 0. Every worker
 * blocks on a shared {@link CountDownLatch} and is released simultaneously, so the requests collide
 * on one row rather than serialising. The exactly-one-winner invariant is asserted three ways:
 * exactly one {@code 200}, exactly {@code N − 1} {@code 409}s, and — read back from the committed row
 * and the HTTP read path — a {@code version} that advanced by exactly one. A single increment is the
 * decisive check: it proves only one write actually committed, closing the gap where two writers
 * could both report success but double-bump the version.
 *
 * <h2>Authentication</h2>
 *
 * <p>Production {@code SecurityConfig} is default-deny with no interactive credential path, so — as
 * in the sibling integration tests — a nested {@link TestSecurityConfig} adds a test-only HTTP Basic
 * filter chain (ordered ahead of the production one) backed by a single in-memory user, letting the
 * concurrent clients drive the real stack as an authenticated caller.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrentStatusTransitionIntegrationTest extends AbstractIntegrationTest {

    private static final String USER = "agent";
    private static final String PASSWORD = "agent-password";

    /** Number of racing threads. Comfortably above 2 so the "N − 1 conflicts" arm is meaningful. */
    private static final int THREAD_COUNT = 12;

    @Autowired private TestRestTemplate restTemplate;

    /**
     * Test-only security so the concurrent clients can authenticate over real HTTP. It preserves the
     * production default-deny rule ({@code anyRequest().authenticated()}) but adds an HTTP Basic
     * mechanism backed by a single in-memory user, ordered ahead of the production chain.
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

    @Test
    void shouldLetExactlyOneOfManyConcurrentTransitionsWinAndReturn409ToTheRest() throws Exception {
        // given — one OPEN ticket (version 0) that all threads will compete over
        UUID ticketId = createOpenTicket("Concurrent transition ticket");
        long initialVersion = readVersion(ticketId);
        assertThat(initialVersion).as("freshly created ticket starts at version 0").isEqualTo(0L);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch readyGate = new CountDownLatch(THREAD_COUNT);
        CountDownLatch doneGate = new CountDownLatch(THREAD_COUNT);
        ConcurrentLinkedQueue<HttpStatusCode> statusCodes = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);

        try {
            // when — every worker issues the SAME OPEN -> IN_PROGRESS transition with the same stale
            // version 0, all released together so the writes genuinely race on one row.
            for (int i = 0; i < THREAD_COUNT; i++) {
                pool.submit(() -> {
                    readyGate.countDown();
                    try {
                        startGate.await();
                        ResponseEntity<JsonNode> response =
                                transition(ticketId, TicketStatus.IN_PROGRESS.name(), 0L);
                        statusCodes.add(response.getStatusCode());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            // Wait until every worker is parked on the gate, then release them simultaneously.
            assertThat(readyGate.await(10, TimeUnit.SECONDS))
                    .as("all workers should be ready before the race starts")
                    .isTrue();
            startGate.countDown();

            assertThat(doneGate.await(30, TimeUnit.SECONDS))
                    .as("all concurrent transitions should complete within the timeout")
                    .isTrue();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
                    .as("executor should shut down cleanly")
                    .isTrue();
        }

        // then — exactly one 200, exactly N - 1 conflicts, and no other status codes leaked in
        assertThat(statusCodes)
                .as("every worker must have recorded a status code")
                .hasSize(THREAD_COUNT);

        long okCount = statusCodes.stream().filter(HttpStatus.OK::equals).count();
        long conflictCount = statusCodes.stream().filter(HttpStatus.CONFLICT::equals).count();

        assertThat(okCount).as("exactly one concurrent transition should succeed").isEqualTo(1L);
        assertThat(conflictCount)
                .as("every losing concurrent transition should be a 409 Conflict (Req 8.9)")
                .isEqualTo(THREAD_COUNT - 1L);
        assertThat(statusCodes)
                .as("only 200 and 409 are valid outcomes for this race")
                .allMatch(code -> code.equals(HttpStatus.OK) || code.equals(HttpStatus.CONFLICT));

        // and — the winner advanced the version by exactly one, proving a single committed write
        long finalVersion = readVersion(ticketId);
        assertThat(finalVersion)
                .as("optimistic locking must bump the version exactly once despite N racing writers (Req 4.6)")
                .isEqualTo(initialVersion + 1L);

        // and — the single committed write is the OPEN -> IN_PROGRESS transition, persisted for real
        assertPersistedStatus(ticketId, TicketStatus.IN_PROGRESS);
        assertThat(readVersionOverHttp(ticketId))
                .as("HTTP read path should agree with the committed row version")
                .isEqualTo(finalVersion);
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
     * Sends {@code PATCH /api/v1/tickets/{id}/status} with the given raw status string and version,
     * returning the raw response so the caller can inspect any status code (200/409).
     */
    private ResponseEntity<JsonNode> transition(UUID ticketId, String rawStatus, long version) {
        Map<String, Object> body = Map.of("status", rawStatus, "version", version);

        return restTemplate.exchange(
                baseUrl() + "/api/v1/tickets/" + ticketId + "/status",
                HttpMethod.PATCH,
                new HttpEntity<>(body, jsonHeaders()),
                JsonNode.class);
    }

    /** Reads the persisted optimistic-locking version directly from the committed {@code ticket} row. */
    private long readVersion(UUID ticketId) {
        Long version =
                jdbcTemplate.queryForObject("SELECT version FROM ticket WHERE id = ?", Long.class, ticketId);
        assertThat(version).as("version column for %s", ticketId).isNotNull();
        return version;
    }

    /** Reads the version through the real GET endpoint so the API surface can be cross-checked. */
    private long readVersionOverHttp(UUID ticketId) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                baseUrl() + "/api/v1/tickets/" + ticketId,
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().get("version").asLong();
    }

    /** Asserts the persisted status both through the HTTP read path and directly against the row. */
    private void assertPersistedStatus(UUID ticketId, TicketStatus expected) {
        String rowStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM ticket WHERE id = ?", String.class, ticketId);
        assertThat(rowStatus)
                .as("persisted row status for %s", ticketId)
                .isEqualTo(expected.name());

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
