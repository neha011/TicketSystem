package com.ticketsystem.ticket.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketsystem.ticket.config.JacksonConfig;
import com.ticketsystem.ticket.config.SecurityConfig;
import com.ticketsystem.ticket.dto.request.CreateCommentRequest;
import com.ticketsystem.ticket.exception.ForbiddenException;
import com.ticketsystem.ticket.service.CommentService;
import com.ticketsystem.ticket.service.TicketService;
import com.ticketsystem.ticket.service.TicketStatusTransitionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code @WebMvcTest} slice tests for the 401/403 HTTP semantics of the ticket endpoints
 * (Requirements 3.4, 3.5), driven through the real {@link SecurityConfig} filter chain and the real
 * {@link GlobalExceptionHandler} so the bodies are produced exactly as in production.
 *
 * <p>Unlike the sibling HTTP-semantics slices, filters are <em>not</em> disabled here: the whole
 * point is to exercise the filter chain that writes a 401 before any controller runs, and the
 * {@code AccessDeniedHandler} that writes a 403. Both 401 and 403 bodies must carry the standard
 * {@link com.ticketsystem.ticket.dto.response.ErrorResponse} shape and no ticket data, comments, or
 * ticket-related fields. The service collaborators are mocked; a 403 raised from the service surfaces
 * through the advice, so both the filter-chain 401 and the domain 403 are asserted.
 */
@WebMvcTest({TicketController.class, CommentController.class})
@Import({SecurityConfig.class, JacksonConfig.class, GlobalExceptionHandler.class})
class TicketControllerSecuritySemanticsTest {

    private static final UUID TICKET_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @MockBean private TicketService ticketService;

    @MockBean private TicketStatusTransitionService statusTransitionService;

    @MockBean private CommentService commentService;

    @Test
    void shouldReturn401WithErrorShapeAndNoTicketDataWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/{id}", TICKET_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/tickets/" + TICKET_ID))
                // A 401 body carries no ticket data, comments, or ticket-related fields (Req 3.4).
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.comments").doesNotExist())
                .andExpect(jsonPath("$.assignee").doesNotExist())
                .andExpect(jsonPath("$.priority").doesNotExist());
    }

    @Test
    void shouldReturn401WithNoTicketDataWhenPostingCommentUnauthenticated() throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCommentRequest("A comment."));

        mockMvc.perform(post("/api/v1/tickets/{id}/comments", TICKET_ID)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.author").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    @WithMockUser
    void shouldReturn403WithErrorShapeAndNoTicketDataWhenServiceForbids() throws Exception {
        given(ticketService.getById(eq(TICKET_ID), any())).willThrow(new ForbiddenException());

        mockMvc.perform(get("/api/v1/tickets/{id}", TICKET_ID))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/tickets/" + TICKET_ID))
                // A 403 body carries no ticket data, comments, or ticket-related fields (Req 3.5).
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.comments").doesNotExist())
                .andExpect(jsonPath("$.assignee").doesNotExist())
                .andExpect(jsonPath("$.priority").doesNotExist());
    }
}
