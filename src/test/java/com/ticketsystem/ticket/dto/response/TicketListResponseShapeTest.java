package com.ticketsystem.ticket.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketsystem.ticket.config.JacksonConfig;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Asserts the composed {@code PagedResponse<TicketSummaryResponse>} body documented for
 * {@code GET /api/v1/tickets} (Requirements 2.3, 2.6, 9.8).
 *
 * <p>The individual DTO tests build their own mapper; this one serializes through the mapper the
 * application actually wires, so the envelope, the ISO-8601 timestamps, and the explicit-null
 * {@code assignee} are verified together under production Jackson settings rather than in isolation.
 */
class TicketListResponseShapeTest {

    private static final Instant CREATED = Instant.parse("2026-09-05T10:15:30Z");

    private static final ApplicationContextRunner CONTEXT_RUNNER = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class);

    private static TicketSummaryResponse unassignedSummary() {
        return new TicketSummaryResponse(
                UUID.fromString("9f1c3b2a-5d4e-4f6a-8b7c-1e2d3f4a5b6c"),
                "Login fails on SSO",
                TicketStatus.OPEN,
                TicketPriority.HIGH,
                null,
                CREATED,
                CREATED,
                0L);
    }

    @Test
    void shouldSerializeTheDocumentedListBody() {
        withListBody(
                PagedResponse.of(List.of(unassignedSummary()), 0, 20, 137L),
                body -> {
                    assertThat(body.fieldNames())
                            .toIterable()
                            .containsExactly("content", "page", "size", "totalElements", "totalPages");
                    assertThat(body.get("page").asInt()).isZero();
                    assertThat(body.get("size").asInt()).isEqualTo(20);
                    assertThat(body.get("totalElements").asLong()).isEqualTo(137L);
                    assertThat(body.get("totalPages").asInt()).isEqualTo(7);

                    JsonNode row = body.get("content").get(0);
                    assertThat(row.fieldNames())
                            .toIterable()
                            .containsExactly(
                                    "id", "title", "status", "priority", "assignee",
                                    "createdAt", "updatedAt", "version");
                    // The list view renders "Unassigned" off this null, so it must be present, not omitted.
                    assertThat(row.hasNonNull("assignee")).isFalse();
                    assertThat(row.has("assignee")).isTrue();
                    assertThat(row.get("createdAt").asText()).isEqualTo("2026-09-05T10:15:30Z");
                    assertThat(row.get("status").asText()).isEqualTo("OPEN");
                    assertThat(row.get("version").asLong()).isZero();
                });
    }

    @Test
    void shouldSerializeAnEmptyDatabaseAsAZeroTotalPageRatherThanAnError() {
        withListBody(
                PagedResponse.<TicketSummaryResponse>empty(0, 20),
                body -> {
                    assertThat(body.get("content")).isEmpty();
                    assertThat(body.get("totalElements").asLong()).isZero();
                    assertThat(body.get("totalPages").asInt()).isZero();
                });
    }

    private static void withListBody(
            PagedResponse<TicketSummaryResponse> page, java.util.function.Consumer<JsonNode> assertions) {
        CONTEXT_RUNNER.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            assertions.accept(mapper.readTree(mapper.writeValueAsString(page)));
        });
    }
}
