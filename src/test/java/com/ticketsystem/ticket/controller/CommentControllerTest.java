package com.ticketsystem.ticket.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketsystem.ticket.dto.request.CreateCommentRequest;
import com.ticketsystem.ticket.dto.response.CommentResponse;
import com.ticketsystem.ticket.exception.ApiException;
import com.ticketsystem.ticket.exception.NotFoundException;
import com.ticketsystem.ticket.service.CommentService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@code @WebMvcTest} slice tests for {@link CommentController}, verifying the HTTP semantics of the
 * comment endpoints (Requirements 3.1, 3.6, 5.2, 5.6) without a servlet container, database, or
 * security filter chain.
 *
 * <p>The controller holds no business logic, so these tests assert only what it is responsible for:
 * the 201 status and {@code Location} header on create, the ticket-scoped read and its ordering, and
 * that a service-signalled missing ticket surfaces as a 404. The real {@code GlobalExceptionHandler}
 * (task 13.3) does not exist yet, so a minimal {@link ApiExceptionTestAdvice} local to this test maps
 * {@link ApiException} onto its declared status — enough to observe the 404 semantic here without
 * pre-empting that task.
 */
@WebMvcTest(CommentController.class)
// These slices assert controller HTTP semantics only; the security filter chain is exercised
// separately by SecurityConfigTest, so it is disabled here to keep the intent stated in the class
// Javadoc ("without a servlet container, database, or security filter chain").
@AutoConfigureMockMvc(addFilters = false)
@Import(CommentControllerTest.ApiExceptionTestAdvice.class)
class CommentControllerTest {

    private static final String COMMENTS_PATH = "/api/v1/tickets/{ticketId}/comments";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @MockBean private CommentService commentService;

    @Test
    void shouldReturn201WithLocationHeaderWhenCommentCreated() throws Exception {
        UUID ticketId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID commentId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        CommentResponse created =
                new CommentResponse(commentId, "a.patel", "Escalated to the platform team.",
                        Instant.parse("2026-09-05T10:15:30Z"));
        given(commentService.addComment(eq(ticketId), any(CreateCommentRequest.class), any()))
                .willReturn(created);

        String body = objectMapper.writeValueAsString(
                new CreateCommentRequest("Escalated to the platform team."));

        mockMvc.perform(post(COMMENTS_PATH, ticketId)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        org.hamcrest.Matchers.endsWith(
                                "/api/v1/tickets/" + ticketId + "/comments/" + commentId)))
                .andExpect(jsonPath("$.id").value(commentId.toString()))
                .andExpect(jsonPath("$.author").value("a.patel"))
                .andExpect(jsonPath("$.content").value("Escalated to the platform team."));
    }

    @Test
    void shouldReturn400WhenCommentContentBlank() throws Exception {
        UUID ticketId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String body = objectMapper.writeValueAsString(new CreateCommentRequest("   "));

        mockMvc.perform(post(COMMENTS_PATH, ticketId)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(commentService);
    }

    @Test
    void shouldReturn404WhenAddingCommentToMissingTicket() throws Exception {
        UUID ticketId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        given(commentService.addComment(eq(ticketId), any(CreateCommentRequest.class), any()))
                .willThrow(new NotFoundException("Ticket " + ticketId + " not found"));

        String body = objectMapper.writeValueAsString(
                new CreateCommentRequest("A valid comment."));

        mockMvc.perform(post(COMMENTS_PATH, ticketId)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnCommentsOldestFirstForTicket() throws Exception {
        UUID ticketId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        CommentResponse oldest =
                new CommentResponse(UUID.randomUUID(), "a.patel", "First",
                        Instant.parse("2026-09-05T10:00:00Z"));
        CommentResponse middle =
                new CommentResponse(UUID.randomUUID(), "b.jones", "Second",
                        Instant.parse("2026-09-05T11:00:00Z"));
        CommentResponse newest =
                new CommentResponse(UUID.randomUUID(), "c.lee", "Third",
                        Instant.parse("2026-09-05T12:00:00Z"));
        given(commentService.getComments(eq(ticketId), any()))
                .willReturn(List.of(oldest, middle, newest));

        mockMvc.perform(get(COMMENTS_PATH, ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].content").value("First"))
                .andExpect(jsonPath("$[1].content").value("Second"))
                .andExpect(jsonPath("$[2].content").value("Third"));
    }

    @Test
    void shouldReturnEmptyArrayWhenTicketHasNoComments() throws Exception {
        UUID ticketId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        given(commentService.getComments(eq(ticketId), any())).willReturn(List.of());

        mockMvc.perform(get(COMMENTS_PATH, ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void shouldReturn404WhenReadingCommentsForMissingTicket() throws Exception {
        UUID ticketId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        given(commentService.getComments(eq(ticketId), any()))
                .willThrow(new NotFoundException("Ticket " + ticketId + " not found"));

        mockMvc.perform(get(COMMENTS_PATH, ticketId))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturn400WhenTicketIdIsNotAUuid() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/not-a-uuid/comments"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(commentService);
    }

    /**
     * Minimal stand-in for the not-yet-implemented {@code GlobalExceptionHandler} (task 13.3),
     * mapping any {@link ApiException} onto the status it declares so these slice tests can observe
     * the 404 semantic. Deliberately local to this test so it is not mistaken for the production
     * advice.
     */
    @RestControllerAdvice
    static class ApiExceptionTestAdvice {

        @ExceptionHandler(ApiException.class)
        ResponseEntity<String> handle(ApiException ex) {
            return ResponseEntity.status(ex.status()).body(ex.clientMessage());
        }
    }
}
