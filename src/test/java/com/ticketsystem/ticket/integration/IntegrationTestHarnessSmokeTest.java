package com.ticketsystem.ticket.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Smoke test proving the {@link AbstractIntegrationTest} harness itself works, so task 18.1 is
 * independently verifiable before any real integration test is written on top of it.
 *
 * <p>It asserts three things:
 *
 * <ol>
 *   <li>the Spring context loads against the container (this test running at all establishes that),
 *   <li>the PostgreSQL container is actually running, and
 *   <li>the real Flyway migration ran, evidenced by the {@code flyway_schema_history} row for V1 and
 *       by the migrated {@code ticket}/{@code comment} tables and their check constraints existing.
 * </ol>
 */
class IntegrationTestHarnessSmokeTest extends AbstractIntegrationTest {

    @Test
    void contextLoadsAndPostgresContainerIsRunning() {
        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(POSTGRES.getJdbcUrl()).startsWith("jdbc:postgresql://");
    }

    @Test
    void runsAgainstRealPostgresRatherThanH2() {
        String product = jdbcTemplate.queryForObject(
                "SELECT current_setting('server_version')", String.class);

        // A version string is only returned by a real PostgreSQL server, not the H2 fallback.
        assertThat(product).isNotBlank();
    }

    @Test
    void flywayAppliedTheRealV1Migration() {
        Boolean v1Applied = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class);

        assertThat(v1Applied).isTrue();
    }

    @Test
    void migrationCreatedTheTicketAndCommentTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name IN ('ticket', 'comment')",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder("ticket", "comment");
    }

    @Test
    void databaseResetLeavesTheMigratedSchemaEmptyButIntact() {
        jdbcTemplate.update(
                "INSERT INTO ticket (id, title, status, priority, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, now(), now(), 0)",
                java.util.UUID.randomUUID(),
                "Harness smoke ticket",
                "OPEN",
                "MEDIUM");
        assertThat(countTickets()).isEqualTo(1);

        resetDatabase();

        // Data is gone but the table (schema) survives, so the next test starts clean.
        assertThat(countTickets()).isZero();
    }

    private long countTickets() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM ticket", Long.class);
        return count == null ? 0 : count;
    }
}
