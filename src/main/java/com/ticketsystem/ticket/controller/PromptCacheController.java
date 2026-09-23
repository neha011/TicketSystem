package com.ticketsystem.ticket.controller;

import com.ticketsystem.ticket.dto.request.SubmitPromptRequest;
import com.ticketsystem.ticket.dto.response.CacheEntryResponse;
import com.ticketsystem.ticket.dto.response.ErrorResponse;
import com.ticketsystem.ticket.dto.response.PromptSubmissionResponse;
import com.ticketsystem.ticket.exception.NotFoundException;
import com.ticketsystem.ticket.service.PromptCacheService;
import com.ticketsystem.ticket.service.result.CacheEntryView;
import com.ticketsystem.ticket.service.result.PromptSubmissionResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * HTTP boundary for the prompt cache under {@code /api/v1/prompt-cache} (Requirements 8.1, 8.2, 8.3,
 * 8.4, 8.5, 8.7).
 *
 * <p>Like the other controllers, this one holds no caching policy. It triggers
 * {@code jakarta.validation} on the request body ({@code @Valid}), maps the service-layer
 * {@link PromptSubmissionResult} / {@link CacheEntryView} onto the transport DTOs, decides the HTTP
 * status ({@code 200} on a hit, {@code 201} on a new entry), and builds the {@code Location} header.
 * All normalization, key derivation, hit/miss handling, eviction, and persistence live in
 * {@link PromptCacheService}, and {@link GlobalExceptionHandler} translates every failure into the
 * standard {@code ErrorResponse} shape.
 */
@RestController
@RequestMapping("/api/v1/prompt-cache")
@Tag(name = "Prompt Cache", description = "Submit prompts to the file-backed cache and inspect cached "
        + "entries by key.")
public class PromptCacheController {

    private final PromptCacheService promptCacheService;

    public PromptCacheController(PromptCacheService promptCacheService) {
        this.promptCacheService = promptCacheService;
    }

    /**
     * Submits a prompt to the cache (Requirements 8.1, 8.2, 8.5).
     *
     * <p>The controller only triggers validation and maps the service outcome to a response: a cache
     * hit against an existing non-expired entry returns {@code 200 OK} with the submission result
     * (Requirement 8.1), while a submission that creates a new entry returns {@code 201 Created} with
     * a {@code Location} header pointing at the new entry (Requirement 8.2). Every caching decision is
     * made in {@link PromptCacheService}.
     *
     * @return 200 OK on a cache hit, or 201 Created with {@code Location:
     *     /api/v1/prompt-cache/{cacheKey}} when a new entry is created
     */
    @Operation(
            summary = "Submit a prompt to the cache",
            description = "Normalizes the prompt, derives its cache key, and either records a hit "
                    + "against an existing non-expired entry (200) or creates a new entry (201 with a "
                    + "Location header). All caching policy is applied by the service.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Cache hit: the derived key matched an existing non-expired entry; "
                        + "cacheHit is true and the stored response is returned when present.",
                content = @Content(schema = @Schema(implementation = PromptSubmissionResponse.class))),
        @ApiResponse(
                responseCode = "201",
                description = "New entry created; Location header identifies it and cacheHit is false.",
                content = @Content(schema = @Schema(implementation = PromptSubmissionResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Body is malformed, missing the prompt, empty after normalization, over "
                        + "100000 characters, or supplies a response for a non-matching key; no entry "
                        + "is created.",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "The caching operation failed (key-derivation collision, write-lock "
                        + "timeout, or persistence failure); prior entries are left unchanged.",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<PromptSubmissionResponse> submit(
            @Valid @RequestBody SubmitPromptRequest request, UriComponentsBuilder uriBuilder) {
        PromptSubmissionResult result = promptCacheService.submit(request);
        PromptSubmissionResponse body = new PromptSubmissionResponse(
                result.cacheKey(), result.cacheHit(), result.cachedResponse().orElse(null));

        if (result.cacheHit()) {
            return ResponseEntity.ok(body);
        }
        URI location = uriBuilder
                .path("/api/v1/prompt-cache/{cacheKey}")
                .buildAndExpand(result.cacheKey())
                .toUri();
        return ResponseEntity.created(location).body(body);
    }

    /**
     * Inspects a cached entry by its key (Requirements 8.3, 8.4).
     *
     * <p>A matching non-expired entry is returned as {@code 200 OK}; the service treats an expired
     * entry as absent and removes it, returning empty, which this method surfaces as a
     * {@link NotFoundException} so {@link GlobalExceptionHandler} answers {@code 404} in the standard
     * error shape.
     *
     * @return 200 OK with the cached entry's inspectable metadata
     * @throws NotFoundException when no matching non-expired entry exists (Requirement 8.4)
     */
    @Operation(
            summary = "Inspect a cached entry by key",
            description = "Returns the inspectable metadata of a matching non-expired entry. An "
                    + "expired or unknown key yields 404 in the standard error shape.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "The matching non-expired entry's metadata.",
                content = @Content(schema = @Schema(implementation = CacheEntryResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "No matching non-expired entry exists for the key.",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{cacheKey}")
    public CacheEntryResponse getByKey(@PathVariable String cacheKey) {
        CacheEntryView view = promptCacheService
                .findByKey(cacheKey)
                .orElseThrow(() -> new NotFoundException("No cached entry found for the given key."));
        return new CacheEntryResponse(
                view.cacheKey(),
                view.promptText(),
                view.createdAt(),
                view.lastAccessedAt(),
                view.hitCount());
    }
}
