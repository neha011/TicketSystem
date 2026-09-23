package com.ticketsystem.ticket.integration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared base class for the integration-test suite (Req 9.1, 9.4).
 *
 * <p>Integration tests run the full Spring context against a <strong>real PostgreSQL engine</strong>
 * supplied by Testcontainers, rather than the H2 compatibility mode used by the {@code @DataJpaTest}
 * slice tests. This is what lets task 18 exercise the actual {@code V1__create_ticket_and_comment.sql}
 * Flyway migration — CHECK constraints, {@code btrim} length guards, {@code ON DELETE CASCADE}, and
 * the {@code TIMESTAMPTZ} columns — against the engine that production uses (Req 9.4).
 *
 * <h2>Singleton container</h2>
 *
 * <p>The container is a {@code static} field started once for the whole JVM test run and never
 * stopped explicitly (the <em>singleton container</em> pattern). Testcontainers reaps it via Ryuk
 * when the JVM exits, so there is no per-class start/stop cost — a single PostgreSQL boot is shared
 * across every subclass. Because the container is started before any Spring context is created, its
 * JDBC coordinates are known in time for {@link #datasourceProperties(DynamicPropertyRegistry)} to
 * point the application datasource at it.
 *
 * <h2>Real migrations</h2>
 *
 * <p>{@link #datasourceProperties} overrides {@code spring.datasource.*} so that, under the active
 * {@code test} profile (Flyway enabled, {@code ddl-auto: validate}), Flyway applies the real
 * migrations to the container during context refresh. Hibernate then only validates the resulting
 * schema, so a drift between the entities and the migration fails the whole suite fast.
 *
 * <h2>State reset between classes and tests</h2>
 *
 * <p>The context (and therefore the container connection) is cached and reused across test classes
 * for speed, so state must be reset explicitly to keep tests independent and order-independent
 * (testing-guidelines: "no shared mutable static state"). {@link #resetDatabase()} truncates the
 * {@code ticket} and {@code comment} tables — cascading through the FK — and runs automatically
 * before every test via {@link #cleanDatabaseBeforeEachTest()}. The Flyway schema itself is left in
 * place; only data is cleared, which is faster than a Flyway clean/migrate cycle and leaves the
 * migrated structure available for the next test.
 *
 * <p>Subclasses get a real HTTP server on a random port ({@link #port}) and can inject
 * {@code TestRestTemplate}/{@code WebTestClient} to drive the API end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    /**
     * Single PostgreSQL container shared across the whole test-class hierarchy. Declared {@code
     * static} and started manually (rather than with the {@code @Container} lifecycle) so the one
     * instance is reused by every subclass instead of being recreated per class.
     */
    @SuppressWarnings("resource") // reaped by Testcontainers' Ryuk on JVM exit, not per-test
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ticketdb")
                    .withUsername("ticket")
                    .withPassword("ticket");

    static {
        POSTGRES.start();
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * Points the application datasource at the Testcontainers PostgreSQL instance so Flyway runs the
     * real migrations against it during context refresh (Req 9.4). The driver is forced to the
     * PostgreSQL driver so the {@code test} profile's H2 fallback URL never wins.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    /**
     * Clears all ticket and comment data before every test so tests are independent and
     * order-independent. The migrated schema (tables, constraints, indexes) is preserved; only rows
     * are removed.
     */
    @BeforeEach
    void cleanDatabaseBeforeEachTest() {
        resetDatabase();
    }

    /**
     * Truncates the domain tables, cascading through the ticket → comment foreign key so no orphaned
     * rows survive. {@code RESTART IDENTITY} is harmless for the UUID keys but keeps the reset
     * deterministic if a sequence is ever added.
     */
    protected void resetDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE comment, ticket RESTART IDENTITY CASCADE");
    }

    /** @return the base URL (including the random port) for driving the running API over HTTP. */
    protected String baseUrl() {
        return "http://localhost:" + port;
    }
}
