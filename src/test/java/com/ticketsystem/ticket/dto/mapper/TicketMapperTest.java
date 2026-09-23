package com.ticketsystem.ticket.dto.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketsystem.ticket.domain.Comment;
import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.request.CreateTicketRequest;
import com.ticketsystem.ticket.dto.response.CommentResponse;
import com.ticketsystem.ticket.dto.response.TicketDetailResponse;
import com.ticketsystem.ticket.dto.response.TicketSummaryResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Example-based tests for {@link TicketMapper}.
 *
 * <p>Property 15 covers mapping fidelity across generated inputs; these tests pin the named cases the
 * property would only reach by chance — a null assignee, a ticket with no comments, the summary
 * projection dropping exactly two fields, and creation defaults coming from the service rather than the
 * request body.
 *
 * <p><strong>Requirements: 1.6, 2.6, 3.1, 5.2</strong>
 */
class TicketMapperTest {

    private static final Instant CREATED = Instant.parse("2026-09-05T10:15:30Z");
    private static final Instant UPDATED = Instant.parse("2026-09-06T08:00:00Z");

    private TicketMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new TicketMapper(new CommentMapper());
    }

    private static Ticket ticket(String assignee, Comment... comments) {
        Ticket ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setTitle("Login fails on SSO");
        ticket.setDescription("Users see a 502 after the identity provider redirect.");
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        ticket.setPriority(TicketPriority.HIGH);
        ticket.setAssignee(assignee);
        ticket.setCreatedAt(CREATED);
        ticket.setUpdatedAt(UPDATED);
        ticket.setVersion(3L);
        for (Comment comment : comments) {
            ticket.addComment(comment);
        }
        return ticket;
    }

    private static Comment comment(String author, String content, Instant createdAt) {
        Comment comment = new Comment();
        comment.setId(UUID.randomUUID());
        comment.setAuthor(author);
        comment.setContent(content);
        comment.setCreatedAt(createdAt);
        return comment;
    }

    @Nested
    @DisplayName("summary projection")
    class ToSummary {

        @Test
        void shouldCopyEveryListFieldIncludingVersion() {
            Ticket entity = ticket("a.patel", comment("b.kim", "looking into it", CREATED));

            TicketSummaryResponse summary = mapper.toSummary(entity);

            assertThat(summary.id()).isEqualTo(entity.getId());
            assertThat(summary.title()).isEqualTo("Login fails on SSO");
            assertThat(summary.status()).isEqualTo(TicketStatus.IN_PROGRESS);
            assertThat(summary.priority()).isEqualTo(TicketPriority.HIGH);
            assertThat(summary.assignee()).isEqualTo("a.patel");
            assertThat(summary.createdAt()).isEqualTo(CREATED);
            assertThat(summary.updatedAt()).isEqualTo(UPDATED);
            assertThat(summary.version()).isEqualTo(3L);
        }

        @Test
        void shouldCarryNullAssigneeThroughRatherThanSubstitutingAPlaceholder() {
            // "Unassigned" is a rendering decision for the UI; the API keeps null so the two states
            // stay distinguishable (Requirement 2.7).
            assertThat(mapper.toSummary(ticket(null)).assignee()).isNull();
        }

        @Test
        void shouldAgreeWithTheDetailResponsesOwnProjection() {
            Ticket entity = ticket("a.patel", comment("b.kim", "note", CREATED));

            assertThat(mapper.toSummary(entity)).isEqualTo(mapper.toDetail(entity).summary());
        }

        @Test
        void shouldMapListsInInputOrderAndHandleEmptyInput() {
            Ticket first = ticket("a.patel");
            Ticket second = ticket(null);

            assertThat(mapper.toSummaryList(List.of(first, second)))
                    .extracting(TicketSummaryResponse::id)
                    .containsExactly(first.getId(), second.getId());
            assertThat(mapper.toSummaryList(List.of())).isEmpty();
            assertThat(mapper.toSummaryList(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("detail mapping")
    class ToDetail {

        @Test
        void shouldIncludeDescriptionAndCommentsInEntityOrder() {
            Comment older = comment("a.patel", "first", CREATED);
            Comment newer = comment("b.kim", "second", CREATED.plusSeconds(60));
            Ticket entity = ticket("a.patel", older, newer);

            TicketDetailResponse detail = mapper.toDetail(entity);

            assertThat(detail.description())
                    .isEqualTo("Users see a 502 after the identity provider redirect.");
            assertThat(detail.comments())
                    .extracting(CommentResponse::content)
                    .containsExactly("first", "second");
            assertThat(detail.comments()).extracting(CommentResponse::id)
                    .containsExactly(older.getId(), newer.getId());
        }

        @Test
        void shouldReturnEmptyCommentsRatherThanNullWhenThereAreNone() {
            assertThat(mapper.toDetail(ticket("a.patel")).comments()).isEmpty();
        }

        @Test
        void shouldRejectNullEntity() {
            assertThatNullPointerException().isThrownBy(() -> mapper.toDetail(null));
        }
    }

    @Nested
    @DisplayName("detail round trip")
    class ToEntity {

        @Test
        void shouldRebuildAnEquivalentEntityIncludingTheCommentAssociation() {
            Ticket original = ticket("a.patel", comment("b.kim", "note", CREATED));

            Ticket rebuilt = mapper.toEntity(mapper.toDetail(original));

            assertThat(rebuilt.getId()).isEqualTo(original.getId());
            assertThat(rebuilt.getTitle()).isEqualTo(original.getTitle());
            assertThat(rebuilt.getDescription()).isEqualTo(original.getDescription());
            assertThat(rebuilt.getStatus()).isEqualTo(original.getStatus());
            assertThat(rebuilt.getPriority()).isEqualTo(original.getPriority());
            assertThat(rebuilt.getAssignee()).isEqualTo(original.getAssignee());
            assertThat(rebuilt.getCreatedAt()).isEqualTo(original.getCreatedAt());
            assertThat(rebuilt.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
            assertThat(rebuilt.getVersion()).isEqualTo(original.getVersion());
            assertThat(rebuilt.getComments()).hasSize(1);
            // Both sides of the association are set, so the reconstructed graph is navigable either way.
            assertThat(rebuilt.getComments().get(0).getTicket()).isSameAs(rebuilt);
        }

        @Test
        void shouldNotRewriteValuesOnTheWayBack() {
            // A stored title is already trimmed; re-trimming here would make the round trip lossy for a
            // legitimately space-bearing value and would break Property 15 at boundary lengths.
            TicketDetailResponse response = new TicketDetailResponse(
                    UUID.randomUUID(), " a b ", " kept ", TicketStatus.OPEN, TicketPriority.LOW,
                    " s ", CREATED, UPDATED, 0L, List.of());

            Ticket rebuilt = mapper.toEntity(response);

            assertThat(rebuilt.getTitle()).isEqualTo(" a b ");
            assertThat(rebuilt.getDescription()).isEqualTo(" kept ");
            assertThat(rebuilt.getAssignee()).isEqualTo(" s ");
        }

        @Test
        void shouldRoundTripBackToAnEqualResponse() {
            TicketDetailResponse original = mapper.toDetail(
                    ticket(null, comment("b.kim", "note", CREATED)));

            assertThat(mapper.toDetail(mapper.toEntity(original))).isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("new entity from a creation request")
    class ToNewEntity {

        @Test
        void shouldApplyTheServiceSuppliedStatusAndTimestampsAndLeaveIdentityToJpa() {
            CreateTicketRequest request = new CreateTicketRequest(
                    "Login fails on SSO", "detail", TicketPriority.CRITICAL, "a.patel");

            Ticket entity = mapper.toNewEntity(request, TicketStatus.OPEN, CREATED);

            assertThat(entity.getStatus()).isEqualTo(TicketStatus.OPEN);
            assertThat(entity.getCreatedAt()).isEqualTo(CREATED);
            assertThat(entity.getUpdatedAt()).isEqualTo(CREATED);
            assertThat(entity.getId()).isNull();
            assertThat(entity.getVersion()).isZero();
            assertThat(entity.getComments()).isEmpty();
        }

        @Test
        void shouldStoreTitleTrimmedAndDescriptionExactlyAsSubmitted() {
            CreateTicketRequest request = new CreateTicketRequest(
                    "  Login fails  ", "  leading space matters here  ", TicketPriority.LOW, null);

            Ticket entity = mapper.toNewEntity(request, TicketStatus.OPEN, CREATED);

            assertThat(entity.getTitle()).isEqualTo("Login fails");
            assertThat(entity.getDescription()).isEqualTo("  leading space matters here  ");
            assertThat(entity.getAssignee()).isNull();
        }

        @Test
        void shouldKeepAMaximumLengthTitleWithinTheColumnWidthAfterTrimming() {
            String title = "x".repeat(200);
            CreateTicketRequest request = new CreateTicketRequest(
                    "\u00A0" + title + "\u00A0", null, TicketPriority.MEDIUM, null);

            Ticket entity = mapper.toNewEntity(request, TicketStatus.OPEN, CREATED);

            assertThat(entity.getTitle()).isEqualTo(title);
            assertThat(entity.getTitle()).hasSize(200);
        }

        @Test
        void shouldRejectNullArguments() {
            CreateTicketRequest request =
                    new CreateTicketRequest("t", null, TicketPriority.LOW, null);

            assertThatNullPointerException()
                    .isThrownBy(() -> mapper.toNewEntity(null, TicketStatus.OPEN, CREATED));
            assertThatNullPointerException()
                    .isThrownBy(() -> mapper.toNewEntity(request, null, CREATED));
            assertThatNullPointerException()
                    .isThrownBy(() -> mapper.toNewEntity(request, TicketStatus.OPEN, null));
        }
    }

    @Test
    void shouldDelegateSingleCommentMappingToTheCommentMapper() {
        Comment entity = comment("a.patel", "note", CREATED);

        CommentResponse response = mapper.toCommentResponse(entity);

        assertThat(response).isEqualTo(new CommentResponse(
                entity.getId(), "a.patel", "note", CREATED));
    }
}
