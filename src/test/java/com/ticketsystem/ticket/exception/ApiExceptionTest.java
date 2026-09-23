package com.ticketsystem.ticket.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.ticket.dto.response.FieldError;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiExceptionTest {

    @Test
    void shouldMapEachSubclassToItsDocumentedStatus() {
        assertThat(new NotFoundException("Ticket not found").status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(new ValidationException("bad input").status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new ConflictException("Cannot transition ticket from CLOSED to OPEN").status())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(new UnprocessableEntityException("unknown assignee").status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void shouldExposeClientMessageForResponseBodies() {
        ApiException exception = new ConflictException("Cannot transition ticket from CLOSED to OPEN");

        assertThat(exception.clientMessage()).isEqualTo("Cannot transition ticket from CLOSED to OPEN");
    }

    @Test
    void shouldDefaultToNoFieldErrors() {
        assertThat(new NotFoundException("Ticket not found").fieldErrors()).isEmpty();
    }

    @Test
    void shouldCarryOneEntryPerFailingField() {
        List<FieldError> supplied = List.of(
                new FieldError("title", "must be 1 to 200 characters after trimming"),
                new FieldError("priority", "must be one of LOW, MEDIUM, HIGH, CRITICAL"));

        ApiException exception = new ValidationException("Validation failed for 2 field(s)", supplied);

        assertThat(exception.fieldErrors()).containsExactlyElementsOf(supplied);
    }

    @Test
    void shouldNotLetCallersMutateFieldErrorsAfterConstruction() {
        List<FieldError> mutable = new ArrayList<>();
        mutable.add(new FieldError("assignee", "no such user"));

        ApiException exception = new UnprocessableIntentFixture("unknown assignee", mutable);
        mutable.clear();

        assertThat(exception.fieldErrors()).hasSize(1);
    }

    /** Local subclass so the immutability check does not depend on any one production subclass. */
    private static final class UnprocessableIntentFixture extends ApiException {
        private UnprocessableIntentFixture(String clientMessage, List<FieldError> fieldErrors) {
            super(clientMessage, fieldErrors);
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
    }
}
