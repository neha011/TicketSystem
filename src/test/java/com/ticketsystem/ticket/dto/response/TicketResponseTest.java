package com.ticketsystem.ticket.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies the ticket and comment response DTOs serialize to the JSON shapes documented in the API
 * contract, and that their invariants hold.
 */
class TicketResponseTest {

    private static final Instant CREATED = Instant.parse("2026-09-05T10:15:30Z");
    private static final Instant UPDATED = Instant.parse("2026-09-06T08:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static TicketSummaryResponse summary(String assignee) {
        return new TicketSummaryResponse(
                UUID.fromString("9f1c3b2a-5d4e-4f6a-8b7c-1e2d3f4a5b6c"),
                "Login fails on SSO",
                TicketStatus.OPEN,
                TicketPriority.HIGH,
                assignee,
                CREATED,
                UPDATED,
                3L);
    }

    private static TicketDetailResponse detail(String description, List<CommentResponse> comments) {
        return new TicketDetailResponse(
                UUID.fromString("9f1c3b2a-5d4e-4f6a-8b7c-1e2d3f4a5b6c"),
                "Login fails on SSO",
                description,
                TicketStatus.OPEN,
                TicketPriority.HIGH,
                "a.patel",
                CREATED,
                UPDATED,
                3L,
                comments);
    }

    private static CommentResponse comment(String content) {
        return new CommentResponse(
                UUID.fromString("3f2a1c44-9b7e-4c1d-8a55-0d2f6b7c1e90"), "a.patel", content, CREATED);
    }

    @Test
    void shouldSerializeSummaryWithTheDocumentedFieldsAndIsoTimestamps() throws Exception {
        String json = objectMapper.writeValueAsString(summary("a.patel"));

        assertThat(objectMapper.readTree(json).fieldNames())
                .toIterable()
                .containsExactly(
                        "id", "title", "status", "priority", "assignee", "createdAt", "updatedAt", "version");
        assertThat(json)
                .contains("\"createdAt\":\"2026-09-05T10:15:30Z\"")
                .contains("\"status\":\"OPEN\"")
                .contains("\"priority\":\"HIGH\"")
                .contains("\"version\":3");
    }

    @Test
    void shouldSerializeUnassignedTicketAsExplicitNullRatherThanOmittingTheField() throws Exception {
        String json = objectMapper.writeValueAsString(summary(null));

        assertThat(json).contains("\"assignee\":null");
        assertThat(objectMapper.readTree(json).has("assignee")).isTrue();
    }

    @Test
    void shouldSerializeDetailWithDescriptionAndComments() throws Exception {
        String json = objectMapper.writeValueAsString(
                detail("Users see a 502 after redirect.", List.of(comment("Escalated."))));

        assertThat(objectMapper.readTree(json).fieldNames())
                .toIterable()
                .containsExactly(
                        "id", "title", "description", "status", "priority", "assignee",
                        "createdAt", "updatedAt", "version", "comments");
        assertThat(objectMapper.readTree(json).get("comments").get(0).fieldNames())
                .toIterable()
                .containsExactly("id", "author", "content", "createdAt");
    }

    @Test
    void shouldRepresentACommentlessTicketAsAnEmptyArray() throws Exception {
        TicketDetailResponse fromNull = detail(null, null);

        assertThat(fromNull.comments()).isEmpty();
        assertThat(objectMapper.writeValueAsString(fromNull)).contains("\"comments\":[]");
    }

    @Test
    void shouldNotExposeTheCallersCommentListForMutation() {
        List<CommentResponse> mutable = new ArrayList<>(List.of(comment("first")));

        TicketDetailResponse response = detail(null, mutable);
        mutable.add(comment("second"));

        assertThat(response.comments()).hasSize(1);
    }

    @Test
    void shouldNarrowDetailToTheSummaryShapeWithoutLosingSharedFields() {
        TicketDetailResponse full = detail("some description", List.of(comment("Escalated.")));

        assertThat(full.summary()).isEqualTo(summary("a.patel"));
    }

    @Test
    void shouldRejectSummaryWithoutIdentityOrTimestamps() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TicketSummaryResponse(
                        null, "t", TicketStatus.OPEN, TicketPriority.LOW, null, CREATED, UPDATED, 0L));
        assertThatNullPointerException()
                .isThrownBy(() -> new TicketSummaryResponse(
                        UUID.randomUUID(), "t", TicketStatus.OPEN, TicketPriority.LOW, null, null, UPDATED, 0L));
        assertThatNullPointerException()
                .isThrownBy(() -> new TicketSummaryResponse(
                        UUID.randomUUID(), "t", null, TicketPriority.LOW, null, CREATED, UPDATED, 0L));
    }

    @Test
    void shouldRejectCommentWithoutAuthorContentOrTimestamp() {
        UUID id = UUID.randomUUID();

        assertThatNullPointerException().isThrownBy(() -> new CommentResponse(id, null, "c", CREATED));
        assertThatNullPointerException().isThrownBy(() -> new CommentResponse(id, "a", null, CREATED));
        assertThatNullPointerException().isThrownBy(() -> new CommentResponse(id, "a", "c", null));
    }
}
