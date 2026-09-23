package com.ticketsystem.ticket.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.ticket.TicketServiceApplication;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.request.CreateCommentRequest;
import com.ticketsystem.ticket.dto.request.CreateTicketRequest;
import com.ticketsystem.ticket.dto.response.CommentResponse;
import com.ticketsystem.ticket.dto.response.PagedResponse;
import com.ticketsystem.ticket.dto.response.TicketDetailResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * End-to-end proof that ticket data survives an application restart against the same database
 * (Requirements 9.1, 9.3, 9.8).
 *
 * <h2>What "restart" means here</h2>
 *
 * <p>The persistence-fidelity property (Property 14 in {@code design.md}) is proven exhaustively at
 * the {@code @DataJpaTest} level; per the design, "an actual container restart is verified once by
 * integration test." This is that test. It reuses the singleton PostgreSQL container from
 * {@link AbstractIntegrationTest} and drives two <em>separate</em> Spring
 * {@link org.springframework.context.ApplicationContext}s against it:
 *
 * <ol>
 *   <li><b>Context A</b> — the {@code @SpringBootTest} context injected into this class (its port is
 *       {@link #port}). Tickets and comments are created here over real HTTP, so Flyway, JPA, the
 *       transaction boundaries, and the {@code TIMESTAMPTZ} round-trip are all exercised.
 *   <li><b>Context B</b> — a second, fully independent application context booted in-test via
 *       {@link SpringApplicationBuilder}, pointed at the <em>same</em> container JDBC URL on its own
 *       random port. Context A is never shut down (a second live context is enough to prove the data
 *       is in the shared database rather than in a per-context cache), but B has its own connection
 *       pool, entity manager, and second-level state — so a read that succeeds through B can only be
 *       reading committed rows from the shared PostgreSQL instance, exactly as a real restart would.
 * </ol>
 *
 * <h2>Avoiding the per-test truncation</h2>
 *
 * <p>{@link AbstractIntegrationTest#resetDatabase()} runs {@code @BeforeEach} and truncates the
 * domain tables. To keep the seed data alive across the simulated restart, the entire
 * seed → restart → assert flow lives inside a single test method, so the truncation runs once before
 * the method and never mid-flight.
 *
 * <h2>Authentication</h2>
 *
 * <p>The production {@link com.ticketsystem.ticket.config.SecurityConfig} chain is default-deny and
 * wires no interactive authentication mechanism, so a real HTTP client cannot authenticate against it
 * on its own. {@link HttpBasicTestSecurity} layers HTTP Basic with a single in-memory user on top for
 * the test only; the real service-layer authorization ({@code DefaultTicketAuthorizationService},
 * which admits any identified principal) still runs unchanged, so the authenticated principal flows
 * through to the controllers and services exactly as in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DataSurvivesRestartIntegrationTest.HttpBasicTestSecurity.class)
class DataSurvivesRestartIntegrationTest extends AbstractIntegrationTest {

    /** Deterministic credentials for the in-memory test user driving the API over HTTP. */
    private static final String TEST_USER = "agent";
    private static final String TEST_PASSWORD = "agent-password";

    @org.springframework.beans.factory.annotation.Autowired
    private TestRestTemplate seedingRestTemplate;

    /**
     * Test-only security: HTTP Basic with one in-memory user, so {@link TestRestTemplate} can
     * authenticate. The service-layer authorization is untouched — it still only requires an
     * identified principal — so this changes how the caller proves who they are, not what they are
     * then allowed to do.
     */
    @TestConfiguration
    static class HttpBasicTestSecurity {

        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .csrf(csrf -> csrf.disable())
                    .build();
        }

        @Bean
        InMemoryUserDetailsManager testUsers() {
            UserDetails user = User.withUsername(TEST_USER)
                    .password("{noop}" + TEST_PASSWORD)
                    .authorities("ROLE_USER")
                    .build();
            return new InMemoryUserDetailsManager(user);
        }
    }

    @Test
    void ticketsAndCommentsSurviveAnApplicationRestartAgainstTheSameDatabase() {
        // ----- Arrange: seed a variety of tickets and comments through context A over real HTTP. -----
        TestRestTemplate contextA = seedingRestTemplate.withBasicAuth(TEST_USER, TEST_PASSWORD);
        String contextAUrl = baseUrl();

        // A ticket with an assignee, a description, and several comments.
        TicketDetailResponse ticketWithComments = createTicket(
                contextA,
                contextAUrl,
                new CreateTicketRequest(
                        "Login fails on SSO",
                        "Users see a 502 after the identity provider redirect.",
                        TicketPriority.HIGH,
                        null));
        CommentResponse firstComment =
                addComment(contextA, contextAUrl, ticketWithComments.id(), "Escalated to platform team.");
        CommentResponse secondComment =
                addComment(contextA, contextAUrl, ticketWithComments.id(), "Reproduced on staging.");

        // A minimal ticket: no description, no assignee, no comments — the null-heavy edge case.
        TicketDetailResponse minimalTicket = createTicket(
                contextA,
                contextAUrl,
                new CreateTicketRequest("Disk almost full", null, TicketPriority.CRITICAL, null));

        // A distinct third ticket so we can assert identifiers stay distinct across the restart.
        TicketDetailResponse thirdTicket = createTicket(
                contextA,
                contextAUrl,
                new CreateTicketRequest(
                        "Refund not processed", "Customer charged twice.", TicketPriority.MEDIUM, null));

        List<TicketDetailResponse> seeded = List.of(ticketWithComments, minimalTicket, thirdTicket);

        // ----- Act: restart. Boot a second, independent context bound to the same container. -----
        try (ConfigurableApplicationContext contextB = startSecondContext()) {
            String contextBUrl = "http://localhost:" + serverPort(contextB);
            TestRestTemplate contextBClient =
                    new TestRestTemplate(TEST_USER, TEST_PASSWORD);

            // ----- Assert: every seeded ticket returns from context B byte-for-byte identical. -----
            for (TicketDetailResponse before : seeded) {
                TicketDetailResponse after = getTicket(contextBClient, contextBUrl, before.id());
                assertTicketIdentical(before, after);
            }

            // The ticket-comment association survives: the same two comments, same order, same fields.
            TicketDetailResponse reread =
                    getTicket(contextBClient, contextBUrl, ticketWithComments.id());
            assertThat(reread.comments())
                    .as("both comments must survive the restart, oldest first, unchanged")
                    .containsExactly(firstComment, secondComment);

            // Identifiers stay distinct across the restart — no id collision or reuse.
            assertThat(seeded.stream().map(TicketDetailResponse::id).distinct().count())
                    .as("distinct tickets keep distinct identifiers after the restart")
                    .isEqualTo(seeded.size());

            // The full list read through context B returns exactly the tickets that were persisted.
            PagedResponse<?> page = listTickets(contextBClient, contextBUrl);
            assertThat(page.totalElements())
                    .as("every persisted ticket is visible after the restart")
                    .isEqualTo(seeded.size());
        }
    }

    /**
     * Requirement 9.8: a database that has never held ticket data returns 200 with an empty result
     * and valid pagination metadata, not an error. The {@code @BeforeEach} reset leaves the schema in
     * place with zero rows, which is exactly the "never held ticket data" state.
     */
    @Test
    void emptyDatabaseReturns200WithEmptyPageAndCoherentPaginationMetadata() {
        TestRestTemplate client = seedingRestTemplate.withBasicAuth(TEST_USER, TEST_PASSWORD);

        ResponseEntity<PagedResponse<Object>> response = client.exchange(
                baseUrl() + "/api/v1/tickets",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode())
                .as("an empty database is a 200, not an error (Req 9.8)")
                .isEqualTo(HttpStatus.OK);

        PagedResponse<Object> body = response.getBody();
        assertThat(body).as("the empty list must still carry a body with metadata").isNotNull();
        assertThat(body.content()).as("no tickets exist, so content is empty").isEmpty();
        assertThat(body.totalElements()).as("total element count is zero").isZero();
        assertThat(body.totalPages()).as("total page count is zero when nothing matches").isZero();
        assertThat(body.page()).as("default page index is 0").isZero();
        assertThat(body.size()).as("default page size is the documented default of 20").isEqualTo(20);
    }

    // ---------------------------------------------------------------------------------------------
    // Second-context ("restart") plumbing
    // ---------------------------------------------------------------------------------------------

    /**
     * Boots a second, independent application context against the same PostgreSQL container on its own
     * random port. Reusing {@link AbstractIntegrationTest#POSTGRES} guarantees both contexts share the
     * same database; a fresh context with its own connection pool and entity manager means a read that
     * succeeds through it proves the data is durable in the database, not cached in context A.
     */
    private ConfigurableApplicationContext startSecondContext() {
        return new SpringApplicationBuilder(TicketServiceApplication.class, HttpBasicTestSecurity.class)
                .profiles("test")
                .properties(
                        "server.port=0",
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                        // The schema already exists from context A's migration; Flyway must find it
                        // already at V1 rather than trying to reapply the migration.
                        "spring.flyway.baseline-on-migrate=false")
                .run();
    }

    private static int serverPort(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------------------------------------

    private TicketDetailResponse createTicket(
            TestRestTemplate client, String baseUrl, CreateTicketRequest request) {
        ResponseEntity<TicketDetailResponse> response = client.postForEntity(
                baseUrl + "/api/v1/tickets", jsonEntity(request), TicketDetailResponse.class);
        assertThat(response.getStatusCode())
                .as("ticket creation must succeed while seeding")
                .isEqualTo(HttpStatus.CREATED);
        TicketDetailResponse body = response.getBody();
        assertThat(body).as("create must return the persisted ticket").isNotNull();
        return body;
    }

    private CommentResponse addComment(
            TestRestTemplate client, String baseUrl, UUID ticketId, String content) {
        ResponseEntity<CommentResponse> response = client.postForEntity(
                baseUrl + "/api/v1/tickets/" + ticketId + "/comments",
                jsonEntity(new CreateCommentRequest(content)),
                CommentResponse.class);
        assertThat(response.getStatusCode())
                .as("comment creation must succeed while seeding")
                .isEqualTo(HttpStatus.CREATED);
        CommentResponse body = response.getBody();
        assertThat(body).as("create must return the persisted comment").isNotNull();
        return body;
    }

    private TicketDetailResponse getTicket(TestRestTemplate client, String baseUrl, UUID id) {
        ResponseEntity<TicketDetailResponse> response = client.getForEntity(
                baseUrl + "/api/v1/tickets/" + id, TicketDetailResponse.class);
        assertThat(response.getStatusCode())
                .as("the persisted ticket must be readable after the restart")
                .isEqualTo(HttpStatus.OK);
        TicketDetailResponse body = response.getBody();
        assertThat(body).as("the ticket read must return a body").isNotNull();
        return body;
    }

    private PagedResponse<Object> listTickets(TestRestTemplate client, String baseUrl) {
        ResponseEntity<PagedResponse<Object>> response = client.exchange(
                baseUrl + "/api/v1/tickets",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PagedResponse<Object> body = response.getBody();
        assertThat(body).isNotNull();
        return body;
    }

    private static HttpEntity<Object> jsonEntity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    /**
     * Asserts field-for-field fidelity: identifier, title, description, status, priority, assignee,
     * and both timestamps must all match what was persisted before the restart (Requirement 9.3).
     */
    private static void assertTicketIdentical(TicketDetailResponse before, TicketDetailResponse after) {
        assertThat(after.id()).as("identifier must survive the restart").isEqualTo(before.id());
        assertThat(after.title()).as("title must survive the restart").isEqualTo(before.title());
        assertThat(after.description())
                .as("description (including null) must survive the restart")
                .isEqualTo(before.description());
        assertThat(after.status())
                .as("status must survive the restart")
                .isEqualTo(before.status());
        assertThat(after.priority())
                .as("priority must survive the restart")
                .isEqualTo(before.priority());
        assertThat(after.assignee())
                .as("assignee (including null) must survive the restart")
                .isEqualTo(before.assignee());
        assertThat(after.createdAt())
                .as("creation timestamp must survive the restart")
                .isEqualTo(before.createdAt());
        assertThat(after.updatedAt())
                .as("last-updated timestamp must survive the restart")
                .isEqualTo(before.updatedAt());
        assertThat(after.status())
                .as("a freshly created ticket must still be OPEN after the restart")
                .isEqualTo(TicketStatus.OPEN);
    }
}
