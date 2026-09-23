package com.ticketsystem.ticket.controller;

import com.ticketsystem.ticket.dto.response.ErrorResponse;
import com.ticketsystem.ticket.dto.response.FieldError;
import com.ticketsystem.ticket.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Centralized translation of every failure into the single {@link ErrorResponse} shape
 * (Requirements 1.8, 10.2, 11.8) and its documented HTTP status.
 *
 * <p>Three rules govern this advice:
 *
 * <ul>
 *   <li><b>One shape for every failure.</b> No handler invents a new body structure; clients branch
 *       on {@code status} and, for field-level failures, read {@code fieldErrors}.
 *   <li><b>Nothing internal escapes.</b> Exception text, SQL fragments, driver messages, and stack
 *       traces are logged server-side at ERROR and never placed in a response body. The 500 handlers
 *       build their message from a constant rather than from {@code exception.getMessage()}
 *       (Requirement 11.8).
 *   <li><b>Domain exceptions carry their status.</b> Each {@link ApiException} subclass declares its
 *       own status and a client-safe message, so the mapping lives with the exception rather than in
 *       a growing {@code if}-chain here.
 * </ul>
 *
 * <p>401 responses are not produced here: unauthenticated requests are rejected in the Spring
 * Security filter chain before any controller or advice is reached (Requirement 3.4).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Single message returned for every 500. Built from a constant so no internal detail (exception
     * text, SQL, stack frames) can leak into a response body (Requirements 1.8, 9.2, 11.8).
     */
    static final String GENERIC_ERROR_MESSAGE =
            "An unexpected error occurred. Please try again later.";

    /** Message returned for a rejected {@link ObjectOptimisticLockingFailureException}. */
    static final String CONCURRENT_MODIFICATION_MESSAGE =
            "The ticket was modified concurrently. Reload it and retry.";

    /** Message returned for a malformed or unreadable request body. */
    static final String UNREADABLE_BODY_MESSAGE =
            "Request body is malformed or contains an invalid value.";

    /** Message returned when a path variable or query parameter cannot be bound to its type. */
    static final String TYPE_MISMATCH_MESSAGE_PREFIX = "Parameter '";

    /**
     * Maps every {@link ApiException} subclass (404, 400, 409, 422, 403) by the status it declares,
     * returning its already-vetted client message and any field-level detail it carries
     * (Requirements 3.2, 4.4, 4.5, 4.6, 5.6, 8.3, 8.9).
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex, HttpServletRequest req) {
        HttpStatus status = ex.status();
        // Domain messages are pre-vetted as client-safe; log at WARN for observability, not ERROR.
        log.warn("API exception on {} {} -> {}: {}",
                req.getMethod(), req.getRequestURI(), status.value(), ex.clientMessage());
        List<FieldError> fieldErrors = ex.fieldErrors().isEmpty() ? null : ex.fieldErrors();
        return build(status, ex.clientMessage(), req, fieldErrors);
    }

    /**
     * Expands an invalid {@code @Valid} request body into a 400 carrying one {@link FieldError} entry
     * for every failing field (Requirements 1.4, 4.3, 4.10, 10.2).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(
            MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<FieldError> fieldErrors = new ArrayList<>();
        for (org.springframework.validation.FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.add(new FieldError(fe.getField(), safeReason(fe.getDefaultMessage())));
        }
        // Class-level (cross-field) errors have no field name; surface them under an empty field key
        // so the count of reported problems still equals the number of failures.
        for (ObjectError ge : ex.getBindingResult().getGlobalErrors()) {
            fieldErrors.add(new FieldError(ge.getObjectName(), safeReason(ge.getDefaultMessage())));
        }
        log.warn("Body validation failed on {} {}: {} field error(s)",
                req.getMethod(), req.getRequestURI(), fieldErrors.size());
        return build(HttpStatus.BAD_REQUEST, validationSummary(fieldErrors.size()), req, fieldErrors);
    }

    /**
     * Expands an invalid {@code @Validated} query parameter set into a 400 carrying one
     * {@link FieldError} entry for every failing parameter (Requirements 2.4, 6.4, 7.3, 10.2).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleParamValidation(
            ConstraintViolationException ex, HttpServletRequest req) {
        List<FieldError> fieldErrors = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            fieldErrors.add(
                    new FieldError(leafField(violation.getPropertyPath()),
                            safeReason(violation.getMessage())));
        }
        log.warn("Query parameter validation failed on {} {}: {} violation(s)",
                req.getMethod(), req.getRequestURI(), fieldErrors.size());
        return build(HttpStatus.BAD_REQUEST, validationSummary(fieldErrors.size()), req, fieldErrors);
    }

    /**
     * Rejects an unparseable request body — malformed JSON, mismatched types, or an undefined enum
     * value in the body — as a 400 (Requirements 1.5, 8.8, 10.3). The parser's raw message is not
     * echoed, since it can quote request fragments and internal type names.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest req) {
        log.warn("Unreadable request body on {} {}: {}",
                req.getMethod(), req.getRequestURI(), ex.getMostSpecificCause().getClass().getSimpleName());
        return build(HttpStatus.BAD_REQUEST, UNREADABLE_BODY_MESSAGE, req, null);
    }

    /**
     * Rejects a value that cannot be bound to its target type — a malformed UUID path variable
     * (Requirement 3.3) or an unknown {@code status} query enum (Requirement 7.3) — as a 400.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        String message = TYPE_MISMATCH_MESSAGE_PREFIX + ex.getName() + "' has an invalid value.";
        log.warn("Type mismatch on {} {}: parameter '{}'",
                req.getMethod(), req.getRequestURI(), ex.getName());
        return build(HttpStatus.BAD_REQUEST, message, req, null);
    }

    /**
     * Surfaces a concurrent-flush conflict as a 409 (Requirements 4.6, 8.9). The optimistic lock
     * failure is expected under contention, so it is logged at WARN, not ERROR.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex, HttpServletRequest req) {
        log.warn("Optimistic locking conflict on {} {}", req.getMethod(), req.getRequestURI());
        return build(HttpStatus.CONFLICT, CONCURRENT_MODIFICATION_MESSAGE, req, null);
    }

    /**
     * Maps a Spring Security authorization denial that escapes the filter chain or method security to
     * a 403 (Requirement 3.5). Carries no ticket data, matching {@code ForbiddenException}.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest req) {
        log.warn("Access denied on {} {}", req.getMethod(), req.getRequestURI());
        return build(HttpStatus.FORBIDDEN,
                "You are not permitted to perform this action.", req, null);
    }

    /**
     * Translates any persistence failure into a 500 with the constant generic message. The full
     * exception (which may contain SQL fragments and driver detail) is logged at ERROR and never
     * placed in the body (Requirements 1.8, 9.2, 11.8).
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handlePersistence(
            DataAccessException ex, HttpServletRequest req) {
        log.error("Persistence failure on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_ERROR_MESSAGE, req, null);
    }

    /**
     * Fail-closed fallback: any exception not matched above becomes a 500 with the constant generic
     * message, never a 200 with a partial body. The full exception is logged at ERROR
     * (Requirements 1.8, 11.8).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_ERROR_MESSAGE, req, null);
    }

    /** Builds the response and the matching HTTP status from a single place. */
    private static ResponseEntity<ErrorResponse> build(
            HttpStatus status, String message, HttpServletRequest req, List<FieldError> fieldErrors) {
        ErrorResponse body = ErrorResponse.of(status, message, req.getRequestURI(), fieldErrors);
        return ResponseEntity.status(status).body(body);
    }

    /** Summary message for a field-level validation failure, mirroring the design's example. */
    private static String validationSummary(int failureCount) {
        return "Validation failed for " + failureCount + " field(s)";
    }

    /**
     * Extracts the trailing property name from a Bean Validation path (e.g. {@code list.keyword} ->
     * {@code keyword}), so query-parameter field names match what the client sent rather than the
     * method-argument prefix.
     */
    private static String leafField(Path propertyPath) {
        String leaf = null;
        for (Path.Node node : propertyPath) {
            leaf = node.getName();
        }
        return (leaf == null) ? propertyPath.toString() : leaf;
    }

    /** Falls back to a constant when a constraint provides no message, so a reason is always present. */
    private static String safeReason(String reason) {
        return (reason == null || reason.isBlank()) ? "is invalid" : reason;
    }
}
