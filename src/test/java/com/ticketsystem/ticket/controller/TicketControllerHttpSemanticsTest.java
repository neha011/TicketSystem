package com.ticketsystem.ticket.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketsystem.ticket.config.JacksonConfig;
import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.domain.TicketStatus;
import com.ticketsystem.ticket.dto.request.CreateTicketRequest;
import com.ticketsystem.ticket.dto.request.StatusTransitionRequest;
import com.ticketsystem.ticket.dto.response.TicketDetailResponse;
import com.ticketsystem.ticket.exception.ConflictException;
import com.ticketsystem.ticket.exception.ForbiddenException;
import com.ticketsystem.ticket.exception.NotFoundException;
import com.ticketsystem.ticket.service.TicketService;
import com.ticketsystem.ticket.service.TicketStatusTransitionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code @WebMvcTest} slice tests for the HTTP semantics of {@link TicketController} (Requirements
 * 1.6, 3.3, 8.7), driven through the real {@link GlobalExceptionHandler} so the status codes and the
 * single {@link com.ticketsystem.ticket.dto.response.ErrorResponse} body shape are asserted exactly
 * as the running application produces them.
 *
 * <p>The security filter chain is disabled ({@code addFilters = false}) so these tests observe
 * controller-and-advice semantics only; the 401/403 arms — which are written by the filter chain
 * before any controller runs — are exercised against the real {@link
 * com.ticketsystem.ticket.config.SecurityConfig} in {@link TicketControllerSecuritySemanticsTest}.
 * {@link JacksonConfig} is imported so the slice serializes and parses exactly as production does.
 */
@WebMvcTest(TicketController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, JacksonConfig.class})
class TicketControllerHttpSemanticsTest {

    private static final String TICKETS_PATH = "/api/v1/tickets";
    private static final String TICKET_BY_ID_PATH = "/api/v1/tickets/{id}";
    private static final String STATUS_PATH = "/api/v1/tickets/{id}/status";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @MockBean private TicketService ticketService;

    @MockBean private TicketStatusTransitionService statusTransitionService;

    @Test
    void shouldReturn201WithLocationHeaderOnTicketCreate() throws Exception {
        UUID ticketId = UUID.fromString("9f1c3b2a-5d4e-4f6a-8b7c-1e2d3f4a5b6c");
        TicketDetailResponse created = detailResponse(ticketId, TicketStatus.OPEN);
        given(ticketService.create(any(CreateTicketRequest.class), any())).willReturn(created);

        String body = objectMapper.writeValueAsString(
                new CreateTicketRequest("Login fails on SSO", "502 after redirect",
                        TicketPriority.HIGH, "a.patel"));

        mockMvc.perform(post(TICKETS_PATH)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        endsWith("/api/v1/tickets/" + ticketId)))
                .andExpect(jsonPath("$.id").value(ticketId.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void shouldReturn400WhenTicketIdPathVariableIsMalformedUuid() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/not-a-uuid"))
                .andExpect(status().isBadRequest())
                // The 400 uses the same error shape as every other failure, and carries no ticket data.
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/tickets/not-a-uuid"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist());

        verifyNoInteractions(ticketService);
    }

    @Test
    void shouldReturn409WithMessageNamingBothCurrentAndRequestedStatusOnRejectedTransition()
            throws Exception {
        UUID ticketId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        // Mirrors the production wording from TicketStatusTransitionValidator#requirePermitted, which
        // names both the current and the requested status (Requirement 8.7).
        given(statusTransitionService.transitionStatus(
                        eq(ticketId), eq(TicketStatus.CLOSED), anyLong(), any()))
                .willThrow(new ConflictException(
                        "Cannot transition ticket from OPEN to CLOSED"));

        String body = objectMapper.writeValueAsString(
                new StatusTransitionRequest(TicketStatus.CLOSED, 3L));

        mockMvc.perform(patch(STATUS_PATH, ticketId)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message",
                        allOf(containsString("OPEN"), containsString("CLOSED"))));
    }

    @Test
    void shouldReturnIdenticalErrorShapeAcrossEveryStatusCode() throws Exception {
        // 400 — malformed UUID path variable.
        assertErrorShape(get("/api/v1/tickets/not-a-uuid"), 400, "Bad Request",
                "/api/v1/tickets/not-a-uuid");

        // 404 — service reports a missing ticket.
        UUID missing = UUID.fromString("22222222-2222-2222-2222-222222222222");
        given(ticketService.getById(eq(missing), any()))
                .willThrow(new NotFoundException("Ticket " + missing + " not found"));
        assertErrorShape(get(TICKET_BY_ID_PATH, missing), 404, "Not Found",
                "/api/v1/tickets/" + missing);

        // 403 — service reports the caller is not permitted.
        UUID forbidden = UUID.fromString("33333333-3333-3333-3333-333333333333");
        given(ticketService.getById(eq(forbidden), any())).willThrow(new ForbiddenException());
        assertErrorShape(get(TICKET_BY_ID_PATH, forbidden), 403, "Forbidden",
                "/api/v1/tickets/" + forbidden);

        // 409 — a rejected status transition.
        UUID conflict = UUID.fromString("44444444-4444-4444-4444-444444444444");
        given(statusTransitionService.transitionStatus(
                        eq(conflict), any(TicketStatus.class), anyLong(), any()))
                .willThrow(new ConflictException("Cannot transition ticket from OPEN to CLOSED"));
        String transitionBody = objectMapper.writeValueAsString(
                new StatusTransitionRequest(TicketStatus.CLOSED, 1L));
        assertErrorShape(
                patch(STATUS_PATH, conflict).contentType("application/json").content(transitionBody),
                409, "Conflict", "/api/v1/tickets/" + conflict + "/status");
    }

    /**
     * Asserts the single {@link com.ticketsystem.ticket.dto.response.ErrorResponse} shape: the four
     * always-present fields plus a status/error consistent with the code, and never any ticket data.
     */
    private void assertErrorShape(
            org.springframework.test.web.servlet.RequestBuilder request,
            int expectedStatus,
            String expectedError,
            String expectedPath)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.error").value(expectedError))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value(expectedPath))
                // No ticket data ever leaks into an error body, regardless of status.
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.comments").doesNotExist())
                .andExpect(jsonPath("$.assignee").doesNotExist());
    }

    private static TicketDetailResponse detailResponse(UUID id, TicketStatus status) {
        Instant now = Instant.parse("2026-09-05T10:15:30Z");
        return new TicketDetailResponse(
                id, "Login fails on SSO", "502 after redirect", status, TicketPriority.HIGH,
                "a.patel", now, now, 0L, List.of());
    }
}
