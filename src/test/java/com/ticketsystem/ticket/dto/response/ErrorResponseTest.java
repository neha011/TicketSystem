package com.ticketsystem.ticket.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorResponseTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule()).findAndRegisterModules();

    @Test
    void shouldDeriveStatusCodeAndReasonPhraseFromStatus() {
        ErrorResponse response = ErrorResponse.of(HttpStatus.NOT_FOUND, "Ticket not found", "/api/v1/tickets/1");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.error()).isEqualTo("Not Found");
        assertThat(response.message()).isEqualTo("Ticket not found");
        assertThat(response.path()).isEqualTo("/api/v1/tickets/1");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void shouldOmitFieldErrorsWhenAbsentOrEmpty() throws Exception {
        ErrorResponse absent = ErrorResponse.of(HttpStatus.NOT_FOUND, "Ticket not found", "/api/v1/tickets/1");
        ErrorResponse empty =
                ErrorResponse.of(HttpStatus.NOT_FOUND, "Ticket not found", "/api/v1/tickets/1", List.of());

        assertThat(absent.fieldErrors()).isNull();
        assertThat(empty.fieldErrors()).isNull();
        assertThat(objectMapper.writeValueAsString(empty)).doesNotContain("fieldErrors");
    }

    @Test
    void shouldSerializeTheDocumentedErrorShape() throws Exception {
        ErrorResponse response = ErrorResponse.of(
                HttpStatus.BAD_REQUEST,
                "Validation failed for 2 field(s)",
                "/api/v1/tickets",
                List.of(
                        new FieldError("title", "must be 1 to 200 characters after trimming"),
                        new FieldError("priority", "must be one of LOW, MEDIUM, HIGH, CRITICAL")));

        String json = objectMapper.writeValueAsString(response);

        assertThat(objectMapper.readTree(json).fieldNames())
                .toIterable()
                .containsExactly("timestamp", "status", "error", "message", "path", "fieldErrors");
        assertThat(json).contains("\"field\":\"title\"").contains("\"reason\":\"must be one of LOW, MEDIUM, HIGH, CRITICAL\"");
    }

    @Test
    void shouldRejectMissingRequiredParts() {
        assertThatNullPointerException()
                .isThrownBy(() -> ErrorResponse.of(HttpStatus.BAD_REQUEST, null, "/api/v1/tickets"));
        assertThatNullPointerException()
                .isThrownBy(() -> ErrorResponse.of(HttpStatus.BAD_REQUEST, "bad input", null));
    }

    @Test
    void shouldRejectFieldErrorWithoutFieldOrReason() {
        assertThatNullPointerException().isThrownBy(() -> new FieldError(null, "reason"));
        assertThatNullPointerException().isThrownBy(() -> new FieldError("title", null));
    }
}
