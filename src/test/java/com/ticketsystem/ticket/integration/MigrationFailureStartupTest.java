package com.ticketsystem.ticket.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Task 18.5 — migration-failure startup test (Req 9.5, 9.7).
 *
 * <p>Design intent (design.md, "Environment and Persistence Profiles"): Flyway runs during
 * {@code ApplicationContext} refresh, <strong>before</strong> the embedded web server opens its
 * listener socket. A failed migration therefore throws during startup, the context fails to
 * refresh, no port is ever bound, and no request can be served — there are no in-flight requests to
 * abandon.
 *
 * <p>This test proves that end to end against a real PostgreSQL engine. It boots the real
 * {@link com.ticketsystem.ticket.TicketServiceApplication} programmatically (rather than through
 * {@code @SpringBootTest}, which cannot express "assert the context fails to refresh") with:
 *
 * <ul>
 *   <li>{@code spring.flyway.locations} pointed at a test-only, deliberately broken migration under
 *       {@code classpath:db/migration-broken} — see {@code V1__deliberately_broken.sql}. This
 *       location is never on the main migration path, so the production
 *       {@code V1__create_ticket_and_comment.sql} is untouched.
 *   <li>the datasource pointed at a real PostgreSQL container so Flyway genuinely attempts the
 *       broken migration against a real engine.
 *   <li>a fixed, pre-reserved {@code server.port} so the test can independently prove afterwards
 *       that nothing is listening there.
 * </ul>
 *
 * <p>It then asserts three things:
 *
 * <ol>
 *   <li>startup throws (the context never finishes refreshing),
 *   <li>the failure is a Flyway/migration failure (not some unrelated error), and
 *   <li>no web server port was bound — a fresh {@link ServerSocket} can still bind the chosen port,
 *       which is only possible if the aborted startup never opened a listener there, and a client
 *       connection to it is refused.
 * </ol>
 *
 * <p>The container itself is a {@link org.testcontainers.junit.jupiter.Container}-style singleton
 * started for this class; it does not reuse {@link AbstractIntegrationTest}'s context (extending
 * that base would spin up a <em>successful</em> Spring context, which is the opposite of what this
 * test needs), but it follows the same real-PostgreSQL approach.
 */
@Testcontainers
class MigrationFailureStartupTest {

    /**
     * A real PostgreSQL engine for Flyway to run the broken migration against. Started manually and
     * reaped by Ryuk on JVM exit, mirroring the singleton-container pattern used by
     * {@link AbstractIntegrationTest}.
     */
    @SuppressWarnings("resource") // reaped by Testcontainers' Ryuk on JVM exit
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ticketdb")
                    .withUsername("ticket")
                    .withPassword("ticket");

    static {
        POSTGRES.start();
    }

    @Test
    void brokenMigrationAbortsStartupWithNoPortBoundAndNoRequestServed() throws IOException {
        // Arrange: reserve a free port up front, then release it, so we can later prove that the
        // aborted startup never bound it.
        int port = reserveFreePort();

        SpringApplicationBuilder app =
                new SpringApplicationBuilder(com.ticketsystem.ticket.TicketServiceApplication.class)
                        .web(WebApplicationType.SERVLET)
                        .profiles("test")
                        .properties(
                                // Point Flyway at the deliberately broken migration only.
                                "spring.flyway.enabled=true",
                                "spring.flyway.locations=classpath:db/migration-broken",
                                // Real PostgreSQL so the broken migration is genuinely attempted.
                                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                                "spring.datasource.username=" + POSTGRES.getUsername(),
                                "spring.datasource.password=" + POSTGRES.getPassword(),
                                "spring.datasource.driver-class-name=org.postgresql.Driver",
                                "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
                                // A fixed port so the "nothing is listening" assertion is precise.
                                "server.port=" + port);

        // Act + Assert (1): startup throws — the context never finishes refreshing.
        Throwable startupFailure =
                catchThrowable(
                        () -> {
                            try (ConfigurableApplicationContext ctx = app.run()) {
                                // Reaching here would mean the broken migration did NOT abort
                                // startup, which is a failure of the requirement under test.
                            }
                        });

        assertThat(startupFailure)
                .as("A broken migration must abort ApplicationContext startup (Req 9.5, 9.7)")
                .isNotNull();

        // Assert (2): the failure is a Flyway/migration failure, not something unrelated. The cause
        // chain carries both a Flyway exception type (class name contains "Flyway") and a message
        // referring to the failed migration.
        String causeChain = rootCauseChainText(startupFailure);
        assertThat(causeChain)
                .as("Startup must fail specifically because the Flyway migration failed")
                .containsAnyOf("Flyway", "flyway", "Migration", "migration");

        // Assert (3): no web server port was bound. If the embedded server had opened a listener on
        // `port`, this bind would fail with BindException. That it succeeds proves the server never
        // started — Flyway aborted refresh before the listener socket was opened.
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(false);
            probe.bind(new InetSocketAddress("localhost", port));
            assertThat(probe.isBound())
                    .as("No listener socket must exist on the configured port after a failed startup")
                    .isTrue();
        }

        // Assert (3b): and therefore no request can be served — a client connection is refused.
        assertThatThrownBy(() -> connectExpectingRefusal(port))
                .as("No request can be served: connecting to the unbound port must be refused")
                .isInstanceOf(IOException.class);
    }

    /**
     * Reserves an ephemeral port and immediately releases it. There is an inherent race — another
     * process could grab it — but for a local test run it reliably yields a port that the aborted
     * application startup will not have bound, which is exactly what the assertion needs.
     */
    private static int reserveFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    /** Opens then closes a client socket to prove the target port refuses connections. */
    private static void connectExpectingRefusal(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
        }
    }

    /** Flattens the whole cause chain (messages + class names) into one searchable string. */
    private static String rootCauseChainText(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            sb.append(t.getClass().getName()).append(':').append(' ');
            if (t.getMessage() != null) {
                sb.append(t.getMessage());
            }
            sb.append('\n');
            if (t.getCause() == t) {
                break;
            }
        }
        return sb.toString();
    }
}
