package com.ticketsystem.ticket.controller;

import static org.hamcrest.Matchers.endsWith;
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
import com.ticketsystem.ticket.config.JacksonConfig;
import com.ticketsystem.ticket.dto.request.CreateCommentRequest;
import com.ticketsystem.ticket.dto.response.CommentResponse;
import com.ticketsystem.ticket.exception.NotFoundException;
import com.ticketsystem.ticket.service.CommentService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code @WebMvcTest} slice tests for the HTTP semantics of {@link CommentController} (Requirements
 * 3.3, 5.2), driven through the real {@link GlobalExceptionHandler} so status codes and the single
 * {@link com.ticketsystem.ticket.dto.response.ErrorResponse} shape match production.
 *
 * <p>Complements the existing {@link CommentControllerTest}, which stands in a minimal local advice;
 * this class wires the production advice so the create-with-{@code Location} semantic and the shared
 * error body are asserted end-to-end at the slice. The security filter chain is disabled here — the
 * 401/403 arms are covered in {@link TicketControllerSecuritySemanticsTest}.
 */
@WebMvcTest(CommentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, JacksonConfig.class})
class CommentControllerHttpSemanticsTest {

    private static final String COMMENTS_PATH = "/api/v1/tickets/{ticketId}/comments";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @MockBean private CommentService commentService;

    @Test
    void shouldReturn201WithLocationHeaderOnCommentCreate() throws Exception {
        UUID ticketId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID commentId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        CommentResponse created = new CommentResponse(
                commentId, "a.patel", "Escalated to the platform team.",
                Instant.parse("2026-09-05T10:15:30Z"));
        given(commentService.addComment(eq(ticketId), any(CreateCommentRequest.class), any()))
                .willReturn(created);

        String body = objectMapper.writeValueAsString(
                new CreateCommentRequest("Escalated to the platform team."));

        mockMvc.perform(post(COMMENTS_PATH, ticketId)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        endsWith("/api/v1/tickets/" + ticketId + "/comments/" + commentId)))
                .andExpect(jsonPath("$.id").value(commentId.toString()))
                .andExpect(jsonPath("$.author").value("a.patel"))
                .andExpect(jsonPath("$.content").value("Escalated to the platform team."));
    }

    @Test
    void shouldReturn400WhenTicketIdPathVariableIsMalformedUuid() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/not-a-uuid/comments"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/tickets/not-a-uuid/comments"))
                // No comment or ticket data leaks into the 400 body.
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.content").doesNotExist());

        verifyNoInteractions(commentService);
    }

    @Test
    void shouldReturn404WithSharedErrorShapeWhenTicketMissingOnCommentCreate() throws Exception {
        UUID ticketId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        given(commentService.addComment(eq(ticketId), any(CreateCommentRequest.class), any()))
                .willThrow(new NotFoundException("Ticket " + ticketId + " not found"));

        String body = objectMapper.writeValueAsString(
                new CreateCommentRequest("A valid comment."));

        mockMvc.perform(post(COMMENTS_PATH, ticketId)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value(
                        "/api/v1/tickets/" + ticketId + "/comments"));
    }
}
