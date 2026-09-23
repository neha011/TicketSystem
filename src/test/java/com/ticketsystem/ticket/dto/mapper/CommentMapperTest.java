package com.ticketsystem.ticket.dto.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketsystem.ticket.domain.Comment;
import com.ticketsystem.ticket.dto.response.CommentResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Example-based tests for {@link CommentMapper}.
 *
 * <p><strong>Requirements: 3.1, 5.2</strong>
 */
class CommentMapperTest {

    private static final Instant NOW = Instant.parse("2026-09-05T10:15:30Z");

    private CommentMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new CommentMapper();
    }

    private static Comment comment(UUID id, String author, String content, Instant createdAt) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setAuthor(author);
        comment.setContent(content);
        comment.setCreatedAt(createdAt);
        return comment;
    }

    @Nested
    @DisplayName("entity to response")
    class ToResponse {

        @Test
        void shouldCopyEveryExposedField() {
            UUID id = UUID.randomUUID();
            Comment entity = comment(id, "a.patel", "Escalated to the platform team.", NOW);

            CommentResponse response = mapper.toResponse(entity);

            assertThat(response.id()).isEqualTo(id);
            assertThat(response.author()).isEqualTo("a.patel");
            assertThat(response.content()).isEqualTo("Escalated to the platform team.");
            assertThat(response.createdAt()).isEqualTo(NOW);
        }

        @Test
        void shouldRejectNullEntity() {
            assertThatNullPointerException().isThrownBy(() -> mapper.toResponse(null));
        }
    }

    @Nested
    @DisplayName("comment list mapping")
    class ToResponseList {

        @Test
        void shouldPreserveInputOrderWithoutResorting() {
            // Deliberately newest-first: the mapper must not impose its own order, since the entity's
            // @OrderBy already owns the (createdAt, id) rule.
            Comment newer = comment(UUID.randomUUID(), "b.kim", "second", NOW.plusSeconds(60));
            Comment older = comment(UUID.randomUUID(), "a.patel", "first", NOW);

            List<CommentResponse> responses = mapper.toResponseList(List.of(newer, older));

            assertThat(responses).extracting(CommentResponse::content).containsExactly("second", "first");
        }

        @Test
        void shouldReturnEmptyListForNullOrEmptyInput() {
            assertThat(mapper.toResponseList(null)).isEmpty();
            assertThat(mapper.toResponseList(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("response to entity")
    class ToEntity {

        @Test
        void shouldRebuildEveryFieldAndLeaveTheTicketUnset() {
            UUID id = UUID.randomUUID();
            CommentResponse response = new CommentResponse(id, "a.patel", "text", NOW);

            Comment entity = mapper.toEntity(response);

            assertThat(entity.getId()).isEqualTo(id);
            assertThat(entity.getAuthor()).isEqualTo("a.patel");
            assertThat(entity.getContent()).isEqualTo("text");
            assertThat(entity.getCreatedAt()).isEqualTo(NOW);
            // The owning side is Ticket.addComment's job, not the mapper's.
            assertThat(entity.getTicket()).isNull();
        }

        @Test
        void shouldRoundTripBackToAnEqualResponse() {
            CommentResponse original =
                    new CommentResponse(UUID.randomUUID(), "a.patel", "  padded stays padded  ", NOW);

            CommentResponse roundTripped = mapper.toResponse(mapper.toEntity(original));

            assertThat(roundTripped).isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("new entity from server-derived parts")
    class ToNewEntity {

        @Test
        void shouldStoreContentTrimmedWithTheSuppliedAuthorAndTimestamp() {
            Comment entity = mapper.toNewEntity("  Escalated.  ", "a.patel", NOW);

            assertThat(entity.getContent()).isEqualTo("Escalated.");
            assertThat(entity.getAuthor()).isEqualTo("a.patel");
            assertThat(entity.getCreatedAt()).isEqualTo(NOW);
            assertThat(entity.getId()).isNull();
        }

        @Test
        void shouldTrimNonBreakingSpaceThatStripWouldKeep() {
            // NBSP survives String.strip(); the column width check would then fail on a maximum-length
            // value, so the mapper trims by the same wider definition the constraint measures with.
            Comment entity = mapper.toNewEntity("\u00A0text\u3000", "a.patel", NOW);

            assertThat(entity.getContent()).isEqualTo("text");
        }

        @Test
        void shouldRejectNullArguments() {
            assertThatNullPointerException().isThrownBy(() -> mapper.toNewEntity(null, "a.patel", NOW));
            assertThatNullPointerException().isThrownBy(() -> mapper.toNewEntity("text", null, NOW));
            assertThatNullPointerException().isThrownBy(() -> mapper.toNewEntity("text", "a.patel", null));
        }
    }
}
