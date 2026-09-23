---
inclusion: always
---

# API Standards

## Style
- REST over HTTP/JSON. Resource-oriented URLs (nouns, plural): `/api/v1/tickets`, `/api/v1/tickets/{id}/comments`.
- Version the API in the URL path (`/api/v1/...`). Breaking changes get a new version, not a silent change to an existing one.
- Use standard HTTP methods correctly:
  - `GET` — read, no side effects, safe to cache.
  - `POST` — create a resource, or trigger a non-idempotent action.
  - `PUT` — full replace of a resource (idempotent).
  - `PATCH` — partial update.
  - `DELETE` — remove a resource (idempotent).

## Documentation
- Document every endpoint with springdoc-openapi (`@Operation`, `@ApiResponse`, `@Schema` annotations) so `/v3/api-docs` and Swagger UI stay accurate automatically.
- Every DTO field with non-obvious constraints should have a `@Schema(description = ...)`.
- Keep OpenAPI docs in sync with code — regenerate/verify as part of the review checklist, don't hand-maintain a separate spec file that can drift.

## Status Codes
- `200 OK` — successful GET/PUT/PATCH.
- `201 Created` — successful POST that creates a resource; include a `Location` header.
- `204 No Content` — successful DELETE or action with no response body.
- `400 Bad Request` — malformed request or validation failure.
- `401 Unauthorized` — missing/invalid authentication.
- `403 Forbidden` — authenticated but not permitted.
- `404 Not Found` — resource doesn't exist.
- `409 Conflict` — state conflict (e.g. duplicate, concurrent modification).
- `422 Unprocessable Entity` — semantically invalid request (business rule violation).
- `500 Internal Server Error` — unhandled failure; never expose internals in the body.

## Request/Response Conventions
- Request and response bodies use `camelCase` JSON field names.
- Use DTOs, never expose JPA entities directly.
- Paginate list endpoints (`page`, `size`, and either `sort` or explicit sort params). Return pagination metadata (`totalElements`, `totalPages`, `page`, `size`) alongside the content array.
- Use ISO-8601 for all dates/times (`2026-09-05T10:15:30Z`).
- Consistent error response shape across all endpoints, e.g.:
  ```json
  {
    "timestamp": "2026-09-05T10:15:30Z",
    "status": 404,
    "error": "Not Found",
    "message": "Ticket 123 not found",
    "path": "/api/v1/tickets/123"
  }
  ```

## Security
- All endpoints require authentication by default; explicitly mark public endpoints (e.g. health checks) rather than defaulting open.
- Enforce authorization checks in the service layer, not just the controller/route level.
- Never accept or return raw secrets/tokens in request/response bodies. Flag any new unauthenticated endpoint explicitly when it's introduced.
- Validate and sanitize all inputs; use parameterized queries (Spring Data JPA does this by default — never build JPQL/SQL via string concatenation with user input).

## Idempotency & Concurrency
- `PUT`/`DELETE` must be idempotent — repeating the same call produces the same end state.
- Use optimistic locking (`@Version`) for resources that can be concurrently updated, and surface conflicts as `409 Conflict`.
