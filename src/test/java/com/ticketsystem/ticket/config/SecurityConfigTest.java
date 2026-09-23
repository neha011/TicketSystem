package com.ticketsystem.ticket.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketsystem.ticket.controller.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slice tests for {@link SecurityConfig}, verifying the default-deny filter chain and the standard
 * {@link com.ticketsystem.ticket.dto.response.ErrorResponse}-shaped 401/403 bodies
 * (Requirements 3.4, 3.5).
 *
 * <p>The real {@link SecurityConfig}, {@link JacksonConfig}, and {@link GlobalExceptionHandler} are
 * imported so the filter chain, the custom {@code AuthenticationEntryPoint}/{@code
 * AccessDeniedHandler}, and the shared {@code ObjectMapper} are exercised as configured in
 * production. A {@link ProbeController} supplies protected routes, the public paths, and a route that
 * throws {@link AccessDeniedException}, so the 401 and 403 arms can both be observed without
 * depending on the real ticket controllers.
 */
@WebMvcTest(controllers = SecurityConfigTest.ProbeController.class)
@Import({
    SecurityConfigTest.ProbeController.class,
    SecurityConfig.class,
    JacksonConfig.class,
    GlobalExceptionHandler.class
})
class SecurityConfigTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void shouldReturn401WithErrorResponseShapeAndNoTicketDataWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value(
                        "/api/v1/tickets/11111111-1111-1111-1111-111111111111"))
                // No ticket data of any kind leaks into the 401 body (Req 3.4).
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.comments").doesNotExist())
                .andExpect(jsonPath("$.assignee").doesNotExist());
    }

    @Test
    @WithMockUser
    void shouldReturn403WithErrorResponseShapeAndNoTicketDataWhenAccessDenied() throws Exception {
        mockMvc.perform(get("/api/v1/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/forbidden"))
                // No ticket data of any kind leaks into the 403 body (Req 3.5).
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.comments").doesNotExist());
    }

    @Test
    @WithMockUser
    void shouldAllowAuthenticatedAccessToProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldAllowUnauthenticatedAccessToPublicActuatorHealth() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void shouldAllowUnauthenticatedAccessToPublicActuatorInfo() throws Exception {
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    void shouldAllowUnauthenticatedAccessToOpenApiDocsOutsideProduction() throws Exception {
        // No active profile in this slice test, so OpenAPI routes are public (Req 3.4).
        mockMvc.perform(get("/v3/api-docs/anything")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    /** Probe endpoints standing in for real controllers so the filter chain has routes to guard. */
    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/tickets/{id}")
        String protectedResource() {
            return "ok";
        }

        @GetMapping("/api/v1/forbidden")
        String forbidden() {
            throw new AccessDeniedException("denied");
        }

        @GetMapping("/actuator/health")
        String health() {
            return "UP";
        }

        @GetMapping("/actuator/info")
        String info() {
            return "info";
        }

        @GetMapping("/v3/api-docs/anything")
        String apiDocs() {
            return "docs";
        }

        @GetMapping("/swagger-ui/index.html")
        String swaggerUi() {
            return "ui";
        }
    }
}
