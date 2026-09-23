package com.ticketsystem.ticket.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Full-context test asserting the springdoc-generated OpenAPI document covers every endpoint with
 * its documented status codes (Requirements 1.6, 2.3, 3.1, 4.3, 5.2, 8.2).
 *
 * <p>This boots the whole application on a random port under the default (non-production)
 * {@code local} profile, so springdoc generates {@code /v3/api-docs} from the live
 * {@code @Operation}/{@code @ApiResponse} annotations and {@link
 * com.ticketsystem.ticket.config.SecurityConfig} permits the route (it is only authenticated under
 * {@code prod}/{@code staging}). Fetching the document and asserting against it here is what stops
 * the published spec from silently drifting away from the controllers: if a handler loses an
 * {@code @ApiResponse}, changes its path, or drops a status code, the generated document changes and
 * this test fails.
 *
 * <p>Each expected endpoint is declared once, as an {@link EndpointContract} carrying its path, HTTP
 * method, and the exact set of documented status codes. The assertions are two-fold: the path+method
 * exists in the document, and its {@code responses} map contains every documented status code. The
 * status-code check is a superset assertion on purpose — springdoc may inject framework defaults
 * (for example a generic {@code 200}) that are not worth pinning — but every code the code documents
 * must be present, so a dropped {@code @ApiResponse} is still caught.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiDocumentCoverageTest {

    @Autowired private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** The endpoints and documented status codes that the generated spec must always cover. */
    private static final List<EndpointContract> EXPECTED_ENDPOINTS = List.of(
            new EndpointContract(
                    "/api/v1/tickets", "post", Set.of("201", "400", "401", "403", "500")),
            new EndpointContract(
                    "/api/v1/tickets", "get", Set.of("200", "400", "401", "403")),
            new EndpointContract(
                    "/api/v1/tickets/{id}", "get", Set.of("200", "400", "401", "403", "404")),
            new EndpointContract(
                    "/api/v1/tickets/{id}",
                    "patch",
                    Set.of("200", "400", "401", "403", "404", "409", "422")),
            new EndpointContract(
                    "/api/v1/tickets/{id}/status",
                    "patch",
                    Set.of("200", "400", "401", "403", "404", "409")),
            new EndpointContract(
                    "/api/v1/tickets/{ticketId}/comments",
                    "post",
                    Set.of("201", "400", "401", "403", "404")),
            new EndpointContract(
                    "/api/v1/tickets/{ticketId}/comments",
                    "get",
                    Set.of("200", "401", "403", "404")),
            new EndpointContract(
                    "/api/v1/prompt-cache", "post", Set.of("200", "201", "400", "500")),
            new EndpointContract(
                    "/api/v1/prompt-cache/{cacheKey}", "get", Set.of("200", "404")));

    private JsonNode apiDocs;

    @BeforeAll
    void fetchGeneratedOpenApiDocument() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode())
                .as("springdoc must expose /v3/api-docs under the non-production profile")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).as("the generated OpenAPI document must not be empty").isNotBlank();

        apiDocs = objectMapper.readTree(response.getBody());
    }

    @Test
    void shouldDocumentEveryExpectedPathAndMethod() {
        JsonNode paths = apiDocs.get("paths");
        assertThat(paths).as("the generated document must carry a paths object").isNotNull();

        for (EndpointContract endpoint : EXPECTED_ENDPOINTS) {
            JsonNode pathNode = paths.get(endpoint.path());
            assertThat(pathNode)
                    .as("path %s must be present in the generated OpenAPI document", endpoint.path())
                    .isNotNull();

            assertThat(pathNode.has(endpoint.method()))
                    .as(
                            "operation %s %s must be documented in the generated OpenAPI document",
                            endpoint.method().toUpperCase(), endpoint.path())
                    .isTrue();
        }
    }

    @Test
    void shouldDocumentEveryStatusCodeForEveryOperation() {
        JsonNode paths = apiDocs.get("paths");
        assertThat(paths).as("the generated document must carry a paths object").isNotNull();

        for (EndpointContract endpoint : EXPECTED_ENDPOINTS) {
            Set<String> documentedCodes = documentedStatusCodes(paths, endpoint);

            assertThat(documentedCodes)
                    .as(
                            "operation %s %s must document status codes %s",
                            endpoint.method().toUpperCase(),
                            endpoint.path(),
                            endpoint.statusCodes())
                    .containsAll(endpoint.statusCodes());
        }
    }

    /**
     * Extracts the set of response status codes documented for a single operation in the generated
     * document, failing the test if the path, operation, or responses map is missing.
     */
    private Set<String> documentedStatusCodes(JsonNode paths, EndpointContract endpoint) {
        JsonNode pathNode = paths.get(endpoint.path());
        assertThat(pathNode)
                .as("path %s must be present in the generated OpenAPI document", endpoint.path())
                .isNotNull();

        JsonNode operationNode = pathNode.get(endpoint.method());
        assertThat(operationNode)
                .as(
                        "operation %s %s must be documented in the generated OpenAPI document",
                        endpoint.method().toUpperCase(), endpoint.path())
                .isNotNull();

        JsonNode responses = operationNode.get("responses");
        assertThat(responses)
                .as(
                        "operation %s %s must declare a responses map",
                        endpoint.method().toUpperCase(), endpoint.path())
                .isNotNull();

        Set<String> codes = new LinkedHashSet<>();
        responses.fieldNames().forEachRemaining(codes::add);
        return codes;
    }

    @Test
    void shouldExposeApiTitleSoTheDocumentIsSelfDescribing() {
        JsonNode info = apiDocs.get("info");
        assertThat(info).as("the generated document must carry top-level info metadata").isNotNull();
        assertThat(info.path("title").asText())
                .as("the OpenApiConfig title must reach the generated document")
                .isEqualTo("Support Ticket Management API");
    }

    @Test
    void shouldNotDocumentUnexpectedTicketEndpoints() {
        // Guards the other direction: the paths under /api/v1 in the document are exactly the ones the
        // controllers expose, so an accidental new endpoint (or a renamed path) is caught too.
        JsonNode paths = apiDocs.get("paths");
        Set<String> documentedApiPaths = new LinkedHashSet<>();
        paths.fieldNames().forEachRemaining(name -> {
            if (name.startsWith("/api/")) {
                documentedApiPaths.add(name);
            }
        });

        Set<String> expectedApiPaths =
                EXPECTED_ENDPOINTS.stream().map(EndpointContract::path).collect(Collectors.toSet());

        assertThat(documentedApiPaths)
                .as("every /api path in the generated document must be an expected, contracted path")
                .isEqualTo(expectedApiPaths);
    }

    /**
     * A single documented endpoint: its path template, lower-case HTTP method (matching the OpenAPI
     * operation key), and the exact set of status codes the code documents for it.
     */
    private record EndpointContract(String path, String method, Set<String> statusCodes) {}
}
