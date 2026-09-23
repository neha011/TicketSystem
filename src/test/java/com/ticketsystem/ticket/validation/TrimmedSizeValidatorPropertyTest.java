package com.ticketsystem.ticket.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.ticket.domain.TicketPriority;
import com.ticketsystem.ticket.dto.request.CreateCommentRequest;
import com.ticketsystem.ticket.dto.request.CreateTicketRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;

/**
 * Property-based tests for {@link TrimmedSize} / {@link TrimmedSizeValidator}.
 *
 * <p>Rejection is asserted through a real Bean Validation {@link Validator} against the actual request
 * DTOs rather than against the validator in isolation. That is what makes the property meaningful: the
 * claim is about what happens to a <em>submitted</em> title or comment, so the annotated DTO, the
 * annotation's wiring, and the measuring logic all have to agree.
 *
 * <p><strong>Requirements: 1.3, 5.4, 5.8, 10.4</strong>
 */
class TrimmedSizeValidatorPropertyTest {

    /**
     * A deliberately wide whitespace set, chosen so the property fails against the narrower trimming
     * strategies a reasonable implementation might reach for:
     *
     * <ul>
     *   <li>{@code String.trim()} stops at {@code U+0020} and would keep {@code U+1680} and {@code U+3000}
     *   <li>{@code String.strip()} uses {@code Character.isWhitespace}, which deliberately excludes the
     *       non-breaking spaces, so it would keep {@code U+00A0}
     * </ul>
     *
     * All are BMP characters, so a generated string's {@code char} length equals its character count and
     * the 0..300 length bound is unambiguous.
     */
    private static final char[] WHITESPACE = {
        ' ', // space
        '\t', // tab
        '\r', // carriage return
        '\n', // line feed
        '\f', // form feed
        '\u000B', // vertical tab
        '\u00A0', // no-break space
        '\u3000', // ideographic space
        '\u1680', // Ogham space mark
    };

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeContainer
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterContainer
    static void closeValidator() {
        factory.close();
    }

    // Feature: support-ticket-management, Property 9: Trimmed-length validation treats whitespace-only
    // input as zero-length
    /**
     * Property 9: for any string made up entirely of whitespace — the empty string included — the
     * effective length is 0 and the value is rejected, identically for a ticket title and for comment
     * content.
     *
     * <p>Three things are asserted together, because each covers a different way the rule could break:
     *
     * <ol>
     *   <li>the measured trimmed length is 0, which is the definition the requirement is written in
     *       terms of;
     *   <li>both DTOs report a violation on exactly the offending field, so the rejection actually
     *       reaches a client as a field error rather than being silently absorbed;
     *   <li>the DTO still holds the raw submitted value, so the verdict demonstrably came from the raw
     *       value and not from something trimmed or sanitized upstream (Requirement 5.8).
     * </ol>
     *
     * <p><strong>Validates: Requirements 1.3, 5.4, 5.8, 10.4</strong>
     */
    @Property(tries = 300)
    void whitespaceOnlyInputIsZeroLengthAndRejectedForBothTitleAndContent(
            @ForAll("whitespaceOnlyStrings") String raw) {

        assertThat(TrimmedSizeValidator.trimmedLength(raw))
                .as("effective length of the %d-character whitespace-only value %s", raw.length(), escaped(raw))
                .isZero();

        CreateTicketRequest ticket = new CreateTicketRequest(raw, "A valid description.", TicketPriority.HIGH, null);
        CreateCommentRequest comment = new CreateCommentRequest(raw);

        assertThat(violatedFields(ticket))
                .as("whitespace-only title %s should be rejected, and nothing else", escaped(raw))
                .containsExactly("title");

        assertThat(violatedFields(comment))
                .as("whitespace-only comment content %s should be rejected, and nothing else", escaped(raw))
                .containsExactly("content");

        // The verdict is a function of the raw value: nothing trimmed or rewrote it on the way in.
        assertThat(ticket.title()).as("title must reach validation untouched").isEqualTo(raw);
        assertThat(comment.content()).as("content must reach validation untouched").isEqualTo(raw);
    }

    /**
     * Whitespace-only strings of length 0..300, mixing every character in {@link #WHITESPACE} freely.
     *
     * <p>Length 0 is included because the empty string is the degenerate whitespace-only value the
     * requirement calls out explicitly, and 300 comfortably exceeds the 200-character title bound so
     * over-long blank values are covered too — they must be rejected for being blank, not for being long.
     */
    @Provide
    Arbitrary<String> whitespaceOnlyStrings() {
        return Arbitraries.strings().withChars(WHITESPACE).ofMinLength(0).ofMaxLength(300);
    }

    /** @return the property paths of every violated constraint, which is what {@code fieldErrors} reports. */
    private static Set<String> violatedFields(Object request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    /** Whitespace is invisible in a failure report, so escape it into something readable. */
    private static String escaped(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 2).append('"');
        raw.codePoints().forEach(codePoint -> out.append(String.format("\\u%04X", codePoint)));
        return out.append('"').toString();
    }
}
