package com.ticketsystem.ticket.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * OpenAPI / Swagger documentation wiring, active only outside production.
 *
 * <p>The {@code @Profile("!prod & !staging")} guard means this configuration — and the customized
 * OpenAPI document it contributes — is never created under the {@code prod} or {@code staging}
 * profiles. Combined with {@link SecurityConfig}, which permits the {@code /v3/api-docs} and
 * {@code /swagger-ui} routes only outside those same profiles, the documentation surface is exposed
 * exclusively in non-production environments. The two guards are kept deliberately consistent: the
 * routes and the document they describe appear and disappear together.
 *
 * <p>springdoc auto-configures the endpoints; this bean only enriches the generated document with
 * top-level API metadata (title, version, description) so the published spec is self-describing. The
 * per-endpoint {@code @Operation}/{@code @ApiResponse} and per-field {@code @Schema} annotations live
 * on the controllers and DTOs, so the document stays generated from the code rather than
 * hand-maintained.
 */
@Configuration
@Profile("!prod & !staging")
public class OpenApiConfig {

    /**
     * @return the top-level OpenAPI metadata merged into the springdoc-generated document; the paths,
     *     operations, and schemas themselves come from the annotated controllers and DTOs.
     */
    @Bean
    public OpenAPI ticketServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Support Ticket Management API")
                        .version("v1")
                        .description(
                                "REST API for creating, tracking, and resolving support tickets. "
                                        + "All endpoints require authentication and are versioned "
                                        + "under /api/v1. This documentation is exposed in non-"
                                        + "production profiles only.")
                        .contact(new Contact().name("Ticket Service Team"))
                        .license(new License().name("Proprietary")));
    }
}
