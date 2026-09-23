package com.ticketsystem.ticket.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketsystem.ticket.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Default-deny HTTP security for the ticket service.
 *
 * <p>The filter chain closes with {@code anyRequest().authenticated()}, so a newly added endpoint is
 * protected by default rather than by remembering to protect it (Requirement 3.4). The only public
 * endpoints are the two actuator probes ({@code /actuator/health}, {@code /actuator/info}) and, in
 * non-production profiles only, the OpenAPI documentation routes. Swagger UI and {@code /v3/api-docs}
 * are never exposed under the {@code prod} or {@code staging} profiles; any future unauthenticated
 * endpoint must be added to the public list explicitly and flagged in review.
 *
 * <p>Rejections are written by a custom {@link AuthenticationEntryPoint} (401) and
 * {@link AccessDeniedHandler} (403) that emit the standard {@link ErrorResponse} shape and nothing
 * else — no ticket data, comments, or other ticket-related fields ever appear in a 401/403 body
 * (Requirements 3.4, 3.5). Because unauthenticated requests are rejected in the filter chain before
 * any controller or {@code @RestControllerAdvice} runs, these two handlers — not
 * {@code GlobalExceptionHandler} — own the 401/403 response bodies.
 *
 * <p>Sessions are stateless: the service holds no server-side session and CSRF protection is disabled
 * accordingly, since there is no cookie-based session for a forged request to ride on.
 */
@Configuration
public class SecurityConfig {

    /** OpenAPI documentation routes, public only outside production profiles. */
    private static final String[] OPENAPI_PATHS = {
        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    /** Actuator probes that are always public regardless of profile. */
    private static final String[] PUBLIC_ACTUATOR_PATHS = {"/actuator/health", "/actuator/info"};

    /** Profiles treated as production, where OpenAPI routes must remain authenticated. */
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "staging");

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler,
            Environment environment)
            throws Exception {
        boolean production = isProduction(environment);
        return http.authorizeHttpRequests(auth -> {
                    auth.requestMatchers(PUBLIC_ACTUATOR_PATHS).permitAll();
                    // OpenAPI docs are public only outside production (Requirement 3.4).
                    if (!production) {
                        auth.requestMatchers(OPENAPI_PATHS).permitAll();
                    }
                    auth.anyRequest().authenticated(); // default deny — the closing rule
                })
                .exceptionHandling(e -> e.authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    /**
     * Writes a 401 in the standard {@link ErrorResponse} shape when authentication is missing or
     * invalid. The body carries no ticket data, comments, or other ticket-related fields
     * (Requirement 3.4).
     */
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) ->
                writeError(objectMapper, response, HttpStatus.UNAUTHORIZED, request);
    }

    /**
     * Writes a 403 in the standard {@link ErrorResponse} shape when an authenticated caller is not
     * permitted. The body carries no ticket data, comments, or other ticket-related fields
     * (Requirement 3.5).
     */
    @Bean
    public AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) ->
                writeError(objectMapper, response, HttpStatus.FORBIDDEN, request);
    }

    private static boolean isProduction(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if (PRODUCTION_PROFILES.contains(profile)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Serializes a client-safe {@link ErrorResponse} to the servlet response. The message is a
     * constant summary per status; the request path is included but no resource data is.
     */
    private static void writeError(
            ObjectMapper objectMapper,
            HttpServletResponse response,
            HttpStatus status,
            HttpServletRequest request)
            throws IOException {
        ErrorResponse body = ErrorResponse.of(status, messageFor(status), request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }

    private static String messageFor(HttpStatus status) {
        return status == HttpStatus.UNAUTHORIZED
                ? "Authentication is required to access this resource."
                : "You are not permitted to perform this action.";
    }
}
