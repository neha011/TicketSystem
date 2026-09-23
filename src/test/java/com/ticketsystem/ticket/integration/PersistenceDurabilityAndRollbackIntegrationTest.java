package com.ticketsystem.ticket.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.ticket.config.SecurityConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;

/**
 * Integration tests for the persistence guarantees behind ticket creation and commenting: a change is
 * durable before the success response is returned (Req 9.1), a failure part-way through a multi-step
 * transactional write leaves nothing partially persisted (Req 9.2), and a persistence failure surfaces
 * as a generic 500 rather than being reported as a successful create (Req 1.8).
 *
 * <p>These are task 18.8's three integration scenarios. They run the full application against the real
 * PostgreSQL container provided by {@link AbstractIntegrationTest} and drive the API over HTTP, so the
 * transaction boundaries, commit behaviour, and {@code GlobalExceptionHandler} 500 mapping are all
 * exercised end to end rather than mocked.
 *
 * <h2>Why three separate classes in one file</h2>
 *
 * <p>The durability scenario needs the <em>real</em> repositories so the create actually commits, while
 * the rollback and 500 scenarios need a repository whose {@code save} throws mid-transaction. A
 * {@code @MockBean} replaces the bean for the whole test context, so the three scenarios cannot share one
 * class without the mock leaking into the durability test. They are kept together in this one file, each
 * as its own {@code @SpringBootTest} context, so the task's deliverable stays a single file while each
 * scenario gets exactly the wiring it needs.
 *
 * <h2>Authentication over real HTTP</h2>
 *
 * <p>Every ticket endpoint requires authentication, and the service derives the actor from the
 * authenticated principal. The production {@link SecurityConfig} wires the default-deny chain and the
 * 401/403 bodies but no interactive authentication mechanism (that is the identity provider's job in a
 * real deployment). {@link IntegrationAuthTestConfig} adds a higher-precedence HTTP Basic chain for the
 * {@code /api/**} routes backed by a single in-memory user, so a {@link TestRestTemplate} carrying that
 * user's credentials is authenticated and its username becomes the actor — while an unauthenticated
 * request is still rejected. It changes nothing about the application's own persistence or error
 * behaviour, which is what these tests assert on.
 */
final class PersistenceDurabilityAndRollbackIntegrationTest {

    private PersistenceDurabilityAndRollbackIntegrationTest() {
        // Holder for the three scenario classes below; never instantiated.
    }

    /** Credentials for the in-memory integration-test user. The username becomes the comment/ticket actor. */
    static final String TEST_USER = "test-agent";

    static final String TEST_PASSWORD = "test-password";

    /**
     * Adds an HTTP Basic authentication mechanism for {@code /api/**} on top of the production
     * default-deny policy, so integration tests can drive the authenticated endpoints over real HTTP.
     *
     * <p>The chain is ordered ahead of the application's own so Basic auth is offered for the API routes;
     * it stays stateless and default-deny, so an unauthenticated request is still refused. This exists
     * only to supply a principal in the test environment and asserts nothing on its own.
     */
    @TestConfiguration
    static class IntegrationAuthTestConfig {

        @Bean
        @Order(1)
        SecurityFilterChain integrationTestApiChain(HttpSecurity http) throws Exception {
            return http.securityMatcher("/api/**")
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .build();
        }

        @Bean
        InMemoryUserDetailsManager integrationTestUsers() {
            UserDetails agent = User.withUsername(TEST_USER)
                    .password("{noop}" + TEST_PASSWORD)
                    .authorities("ROLE_USER")
                    .build();
            return new InMemoryUserDetailsManager(agent);
        }
    }

    /** JSON headers plus the in-memory user's Basic credentials, used by every request below. */
    static HttpHeaders authedJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(TEST_USER, TEST_PASSWORD);
        return headers;
    }
}

/**
 * Req 9.1 — a created ticket is committed to the database <em>before</em> the 201 response is returned.
 *
 * <p>After the create call returns, the row is read straight from the database through {@link
 * org.springframework.jdbc.core.JdbcTemplate} on a connection that has no part in the request's
 * persistence context or Hibernate session cache. If the row is visible there, the create transaction
 * must already have committed by the time the caller got its 201 — the guarantee behind "a caller that
 * receives a response knows the ticket is durable". A read through the ORM would prove nothing here,
 * because it could be answered from the session's identity map rather than from committed state.
 */
@ContextConfiguration(
        classes = PersistenceDurabilityAndRollbackIntegrationTest.IntegrationAuthTestConfig.class)
class TicketCreateCommitBeforeResponseIntegrationTest extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldCommitCreatedTicketToTheDatabaseBeforeReturningTheSuccessResponse() {
        // given a well-formed create request
        String uniqueTitle = "Durability check " + UUID.randomUUID();
        String body =
                """
                {"title":"%s","description":"committed before response","priority":"HIGH"}
                """
                        .formatted(uniqueTitle);
        HttpEntity<String> request = new HttpEntity<>(
                body, PersistenceDurabilityAndRollbackIntegrationTest.authedJsonHeaders());

        // when the ticket is created over HTTP
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                baseUrl() + "/api/v1/tickets",
                HttpMethod.POST,
                request,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

        // then the caller sees a 201 with the new id and a Location header
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        String createdId = String.valueOf(response.getBody().get("id"));
        assertThat(createdId).isNotBlank();
        assertThat(response.getHeaders().getLocation())
                .as("a created ticket must carry a Location header")
                .isNotNull();

        // and the row is already committed: a fresh direct-DB read (outside the request's session)
        // sees it, proving the commit happened before the response was returned (Req 9.1).
        Map<String, Object> persisted = jdbcTemplate.queryForMap(
                "SELECT id, title, status, priority FROM ticket WHERE id = ?::uuid", createdId);
        assertThat(persisted.get("title")).isEqualTo(uniqueTitle);
        assertThat(persisted.get("status")).isEqualTo("OPEN");
        assertThat(persisted.get("priority")).isEqualTo("HIGH");
    }

    @Test
    void shouldPersistExactlyOneRowForOneCreate() {
        String uniqueTitle = "Single row " + UUID.randomUUID();
        String body =
                """
                {"title":"%s","priority":"LOW"}
                """
                        .formatted(uniqueTitle);
        HttpEntity<String> request = new HttpEntity<>(
                body, PersistenceDurabilityAndRollbackIntegrationTest.authedJsonHeaders());

        ResponseEntity<String> response =
                restTemplate.postForEntity(baseUrl() + "/api/v1/tickets", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticket WHERE title = ?", Integer.class, uniqueTitle);
        assertThat(count).isEqualTo(1);
    }
}

/**
 * Req 9.2 — a failure part-way through a multi-step transactional write rolls back, leaving no partially
 * persisted data visible to a subsequent read.
 *
 * <p>The genuine multi-step write here is {@code CommentServiceImpl.addComment}, which runs inside one
 * {@code @Transactional} boundary: it loads the ticket, attaches the new comment to the managed ticket's
 * collection (so the in-memory graph is already mutated and the JPA cascade is primed), and only then
 * calls {@code commentRepository.save}. Mocking the comment repository to throw a {@link
 * org.springframework.dao.DataAccessException} on {@code save} reproduces a failure after the first step
 * of the write has already happened.
 *
 * <p>The assertion re-reads through the real database ({@code JdbcTemplate}, outside the rolled-back
 * transaction): no comment row exists for the ticket, and the pre-existing ticket is field-for-field
 * unchanged. Nothing from the half-completed write survived, which is exactly the atomicity Req 9.2
 * demands.
 */
@ContextConfiguration(
        classes = PersistenceDurabilityAndRollbackIntegrationTest.IntegrationAuthTestConfig.class)
class MultiStepWriteRollbackIntegrationTest extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private TestRestTemplate restTemplate;

    // Fails the second step of addComment's transactional write, after the comment has already been
    // attached to the managed ticket, so the rollback has something to undo (Req 9.2).
    @MockBean
    private com.ticketsystem.ticket.repository.CommentRepository commentRepository;

    @Test
    void shouldRollBackAndPersistNothingWhenAMultiStepCommentWriteFailsPartWayThrough() {
        // given a committed ticket in the database
        UUID ticketId = UUID.randomUUID();
        insertOpenTicket(ticketId, "Rollback subject");
        String updatedAtBefore = ticketUpdatedAt(ticketId);

        // and a comment repository whose save fails mid-transaction (after the ticket is already dirtied)
        org.mockito.BDDMockito.given(commentRepository.save(org.mockito.ArgumentMatchers.any()))
                .willThrow(new DataAccessResourceFailureException("simulated persistence failure"));

        // when a comment is posted to the ticket
        String body = """
                {"content":"This write must not be partially persisted."}
                """;
        HttpEntity<String> request = new HttpEntity<>(
                body, PersistenceDurabilityAndRollbackIntegrationTest.authedJsonHeaders());
        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl() + "/api/v1/tickets/" + ticketId + "/comments", request, String.class);

        // then the request fails (the persistence failure is not reported as a success)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // and a subsequent direct-DB read sees no partially persisted comment ...
        Integer commentCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM comment WHERE ticket_id = ?::uuid",
                Integer.class,
                ticketId.toString());
        assertThat(commentCount)
                .as("a rolled-back multi-step write must leave no comment behind")
                .isZero();

        // ... and the pre-existing ticket is untouched by the failed write.
        Map<String, Object> ticketAfter = jdbcTemplate.queryForMap(
                "SELECT title, status, updated_at FROM ticket WHERE id = ?::uuid", ticketId.toString());
        assertThat(ticketAfter.get("title")).isEqualTo("Rollback subject");
        assertThat(ticketAfter.get("status")).isEqualTo("OPEN");
        assertThat(ticketUpdatedAt(ticketId)).isEqualTo(updatedAtBefore);
    }

    /** Inserts a committed OPEN ticket straight through the datasource so the service can load it. */
    private void insertOpenTicket(UUID id, String title) {
        jdbcTemplate.update(
                "INSERT INTO ticket (id, title, status, priority, created_at, updated_at, version) "
                        + "VALUES (?::uuid, ?, 'OPEN', 'MEDIUM', now(), now(), 0)",
                id.toString(),
                title);
    }

    private String ticketUpdatedAt(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at::text FROM ticket WHERE id = ?::uuid", String.class, id.toString());
    }
}

/**
 * Req 1.8 — a persistence failure during create surfaces as a generic 500 and is never reported as a
 * successful create.
 *
 * <p>The ticket repository's {@code save} is mocked to throw a {@link
 * org.springframework.dao.DataAccessException}, simulating the database rejecting the write. The create
 * endpoint must answer 500 (not 201), the body must be the standard {@code ErrorResponse} shape with the
 * constant generic message and no internal detail (no stack trace, SQL, or exception class name), and a
 * subsequent direct-DB read must find no ticket — the failed write left nothing behind and was not
 * quietly reported as created.
 */
@ContextConfiguration(
        classes = PersistenceDurabilityAndRollbackIntegrationTest.IntegrationAuthTestConfig.class)
class PersistenceFailureReportedAsServerErrorIntegrationTest extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private TestRestTemplate restTemplate;

    // Simulates the datasource rejecting the create write (Req 1.8).
    @MockBean
    private com.ticketsystem.ticket.repository.TicketRepository ticketRepository;

    @Test
    void shouldReturnGenericServerErrorAndNotReportSuccessWhenPersistenceFails() {
        // given a repository whose save fails
        org.mockito.BDDMockito.given(ticketRepository.save(org.mockito.ArgumentMatchers.any()))
                .willThrow(new DataAccessResourceFailureException(
                        "org.postgresql.util.PSQLException: connection refused at Driver.java:123"));

        // when a well-formed create request is submitted
        String uniqueTitle = "Persistence failure " + UUID.randomUUID();
        String body =
                """
                {"title":"%s","priority":"CRITICAL"}
                """
                        .formatted(uniqueTitle);
        HttpEntity<String> request = new HttpEntity<>(
                body, PersistenceDurabilityAndRollbackIntegrationTest.authedJsonHeaders());
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                baseUrl() + "/api/v1/tickets",
                HttpMethod.POST,
                request,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

        // then it is a 500, not a reported success
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // and the body is the standard error shape with the generic, leak-free message
        Map<String, Object> errorBody = response.getBody();
        assertThat(errorBody).isNotNull();
        assertThat(errorBody.get("status")).isEqualTo(500);
        assertThat(errorBody.get("error")).isEqualTo("Internal Server Error");
        assertThat(errorBody.get("path")).isEqualTo("/api/v1/tickets");
        assertThat(String.valueOf(errorBody.get("message")))
                .isEqualTo("An unexpected error occurred. Please try again later.");

        // the generic message must not leak any internal detail from the underlying exception
        String bodyText = String.valueOf(errorBody);
        assertThat(bodyText)
                .doesNotContain("PSQLException")
                .doesNotContain("Driver.java")
                .doesNotContain("connection refused")
                .doesNotContain("DataAccessResourceFailureException");

        // and no ticket was persisted: the failure was not reported as a create (Req 1.8)
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticket WHERE title = ?", Integer.class, uniqueTitle);
        assertThat(count).isZero();
    }
}
