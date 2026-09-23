package com.ticketsystem.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ticketsystem.ticket.domain.Comment;
import com.ticketsystem.ticket.domain.Ticket;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.mapper.CommentMapper;
import com.ticketsystem.ticket.dto.request.CreateCommentRequest;
import com.ticketsystem.ticket.dto.response.CommentResponse;
import com.ticketsystem.ticket.exception.NotFoundException;
import com.ticketsystem.ticket.repository.CommentRepository;
import com.ticketsystem.ticket.repository.TicketRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Comment creation behaviour (Requirements 5.1, 5.6, 5.8).
 *
 * <p>The two repositories are mocked because persistence is the boundary; the mapper, the baseline
 * authorization policy, and the clock are the real collaborators, so the assertions are about the
 * {@link CommentResponse} a caller actually receives and the entity that is actually handed to
 * persistence, not a rehearsal of stubbed returns. The clock is fixed so the stored timestamp can be
 * asserted exactly.
 *
 * <p>The "whitespace-only content never reaches persistence" rule (Requirement 5.8) is enforced by
 * {@code @TrimmedSize} at the API boundary, before the service is ever invoked — the service takes an
 * already-validated {@link CreateCommentRequest}. It is therefore asserted at the layer that owns it:
 * a real Bean Validation {@link Validator} rejects a whitespace-only body, which is what keeps such a
 * request from reaching {@link CommentServiceImpl} and, in turn, the repository.
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-05T10:15:30Z");
    private static final String AUTHOR = "alice";

    @Mock private TicketRepository ticketRepository;
    @Mock private CommentRepository commentRepository;

    private CommentService commentService;

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @BeforeEach
    void setUp() {
        commentService =
                new CommentServiceImpl(
                        ticketRepository,
                        commentRepository,
                        new CommentMapper(),
                        new DefaultTicketAuthorizationService(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * Req 5.1, 5.6: the happy path persists a comment attached to the ticket, stamped with the
     * service clock and attributed to the authenticated principal rather than anything in the body.
     */
    @Test
    void shouldCreateCommentWithTimestampAndPrincipalDerivedAuthor() {
        UUID ticketId = UUID.randomUUID();
        Ticket ticket = storedTicket(ticketId);
        given(ticketRepository.findById(ticketId)).willReturn(Optional.of(ticket));
        given(commentRepository.save(any(Comment.class))).willAnswer(saveAssigningId());

        CommentResponse created =
                commentService.addComment(
                        ticketId, new CreateCommentRequest("Escalated to the platform team."), AUTHOR);

        assertThat(created.id()).isNotNull();
        assertThat(created.author()).isEqualTo(AUTHOR);
        assertThat(created.content()).isEqualTo("Escalated to the platform team.");
        assertThat(created.createdAt()).isEqualTo(NOW);

        // The persisted entity carries the same server-derived values and is attached to the ticket.
        ArgumentCaptor<Comment> saved = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(saved.capture());
        assertThat(saved.getValue().getAuthor()).isEqualTo(AUTHOR);
        assertThat(saved.getValue().getContent()).isEqualTo("Escalated to the platform team.");
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getTicket()).isSameAs(ticket);
        assertThat(ticket.getComments()).contains(saved.getValue());
    }

    /**
     * Req 5.6: a comment against a whitespace-laden body still stores the content trimmed, and the
     * author is taken from the principal, never from the request — the DTO carries no author at all.
     */
    @Test
    void shouldStoreContentTrimmedAndIgnoreAnyClientSuppliedAuthor() {
        UUID ticketId = UUID.randomUUID();
        given(ticketRepository.findById(ticketId)).willReturn(Optional.of(storedTicket(ticketId)));
        given(commentRepository.save(any(Comment.class))).willAnswer(saveAssigningId());

        CommentResponse created =
                commentService.addComment(
                        ticketId, new CreateCommentRequest("   trimmed me   "), AUTHOR);

        assertThat(created.content()).isEqualTo("trimmed me");
        assertThat(created.author()).isEqualTo(AUTHOR);
    }

    /**
     * Req 5.6: a comment for an id that names no ticket is a 404 that arrives already carrying its
     * status, and nothing is persisted — the existence check runs before the comment is built.
     */
    @Test
    void shouldReturn404WhenTicketNotFound() {
        UUID ticketId = UUID.randomUUID();
        given(ticketRepository.findById(ticketId)).willReturn(Optional.empty());

        NotFoundException thrown =
                catchThrowableOfType(
                        () ->
                                commentService.addComment(
                                        ticketId,
                                        new CreateCommentRequest("Any comment"),
                                        AUTHOR),
                        NotFoundException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(thrown.clientMessage()).contains(ticketId.toString());
        verify(commentRepository, never()).save(any(Comment.class));
    }

    /**
     * Req 5.8: whitespace-only content never reaches persistence because it never passes the boundary
     * validation the service relies on. Asserted against a real validator, the layer that owns the
     * rule: a whitespace-only body is a constraint violation on {@code content}, so it is rejected
     * before {@link CommentServiceImpl} — and therefore the repository — is ever reached.
     */
    @Test
    void shouldRejectWhitespaceOnlyContentAtValidationBeforeTheServiceIsInvoked() {
        for (String blank : Set.of(" ", "   ", "\t\r\n\f", "\u00A0\u3000")) {
            Set<ConstraintViolation<CreateCommentRequest>> violations =
                    validator.validate(new CreateCommentRequest(blank));

            assertThat(violations)
                    .as("whitespace-only content %s", blank.codePoints().toArray().length)
                    .isNotEmpty();
            assertThat(violations)
                    .extracting(v -> v.getPropertyPath().toString())
                    .contains("content");
        }
    }

    /** Stands in for the database assigning the generated identifier on persist. */
    private static org.mockito.stubbing.Answer<Comment> saveAssigningId() {
        return invocation -> {
            Comment comment = invocation.getArgument(0);
            comment.setId(UUID.randomUUID());
            return comment;
        };
    }

    private static Ticket storedTicket(UUID id) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setTitle("Login fails on SSO");
        ticket.setDescription("502 after redirect");
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        ticket.setPriority(TicketPriority.HIGH);
        ticket.setAssignee("bob");
        ticket.setCreatedAt(NOW);
        ticket.setUpdatedAt(NOW);
        return ticket;
    }
}
