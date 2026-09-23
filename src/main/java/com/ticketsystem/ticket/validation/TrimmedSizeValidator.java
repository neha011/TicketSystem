package com.ticketsystem.ticket.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Enforces {@link TrimmedSize} by stripping leading and trailing whitespace and measuring what is
 * left.
 *
 * <p>The whitespace set is deliberately wider than {@link String#strip()}. {@code strip()} delegates
 * to {@link Character#isWhitespace}, which <em>excludes</em> non-breaking spaces by design — so
 * {@code "\u00A0"} survives {@code strip()} and a non-breaking space would be accepted as a
 * one-character title. That defeats the purpose of the constraint: the Trimmed_Length definition in
 * the requirements glossary covers all leading and trailing whitespace characters, and a
 * visually-blank title is exactly what Requirements 1.3, 5.4, and 10.4 exist to reject. The set is
 * therefore {@code isWhitespace OR isSpaceChar}, which adds NBSP ({@code U+00A0}), narrow NBSP
 * ({@code U+202F}), and figure space ({@code U+2007}) on top of everything {@code strip()} removes.
 * {@code trim()} would be narrower still — it stops at {@code U+0020} and would keep ideographic
 * space ({@code U+3000}) and Ogham space mark ({@code U+1680}).
 *
 * <p>The value is measured, never rewritten: this validator has no side effects and the raw submitted
 * value is what continues downstream, which is what makes the verdict a function of the raw value
 * (Requirement 5.8).
 *
 * <p>Stateless and thread-safe once {@link #initialize} has run, as the Bean Validation contract
 * requires.
 *
 * <p><strong>Requirements: 1.3, 1.4, 4.3, 5.4, 5.5, 10.1, 10.4</strong>
 */
public class TrimmedSizeValidator implements ConstraintValidator<TrimmedSize, CharSequence> {

    private int min;
    private int max;

    @Override
    public void initialize(TrimmedSize constraint) {
        this.min = constraint.min();
        this.max = constraint.max();

        // A nonsensical bound is a programming error in the DTO, not a bad request. Failing here
        // surfaces it at context startup rather than letting the constraint silently reject
        // everything at runtime.
        if (min < 0) {
            throw new IllegalArgumentException("@TrimmedSize min must not be negative, was " + min);
        }
        if (max < min) {
            throw new IllegalArgumentException(
                    "@TrimmedSize max (" + max + ") must not be less than min (" + min + ")");
        }
    }

    /**
     * @return true when {@code value} is null, or when its trimmed length falls within
     *     {@code [min, max]}. Null passes by the Bean Validation convention shared with
     *     {@code @Size}; presence is {@code @NotNull}'s job so that a missing field and a blank field
     *     produce distinct client-facing reasons.
     */
    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        int length = trimmedLength(value);
        return length >= min && length <= max;
    }

    /**
     * The Trimmed_Length of {@code value}: its length in {@code char} units after leading and
     * trailing whitespace is removed. A whitespace-only value therefore measures 0, which is the
     * whole point of the constraint (Requirements 1.3, 5.4, 10.4).
     *
     * <p>Exposed so callers and tests can assert the effective length directly instead of inferring
     * it from a boolean verdict.
     *
     * <p>Measured in {@code char} units, matching both {@code @Size} and the {@code VARCHAR} column
     * widths this constraint shadows, so a value that passes validation always fits its column.
     * Iteration is by code point so a supplementary character (an emoji, say) is never split, but the
     * returned count stays in {@code char} units for that same column-safety reason.
     */
    public static int trimmedLength(CharSequence value) {
        String text = value.toString();
        int length = text.length();

        int start = 0;
        while (start < length) {
            int codePoint = text.codePointAt(start);
            if (!isTrimmable(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }

        // Fully blank: bail out before scanning backwards, otherwise the two scans cross over.
        if (start == length) {
            return 0;
        }

        int end = length;
        while (end > start) {
            int codePoint = text.codePointBefore(end);
            if (!isTrimmable(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }

        return end - start;
    }

    /**
     * {@code value} with the same leading and trailing whitespace {@link #trimmedLength} discounts
     * removed, so {@code trim(v).length() == trimmedLength(v)} for every {@code v}.
     *
     * <p>Mappers store text trimmed, and they must trim by exactly this definition rather than
     * {@link String#trim()} or {@link String#strip()}: a title of 200 trimmed characters padded with
     * non-breaking spaces passes validation, and a narrower trim would leave those pad characters in
     * place and overflow the {@code VARCHAR(200)} column.
     *
     * @param value the text to trim
     * @return the trimmed text, or the empty string when {@code value} is entirely whitespace
     */
    public static String trim(CharSequence value) {
        String text = value.toString();
        int trimmed = trimmedLength(text);
        if (trimmed == text.length()) {
            return text;
        }
        if (trimmed == 0) {
            return "";
        }

        int start = 0;
        while (isTrimmable(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        return text.substring(start, start + trimmed);
    }

    /**
     * {@code isWhitespace} covers tab, CR, LF, form feed, vertical tab, and Unicode separators such as
     * ideographic space; {@code isSpaceChar} adds the non-breaking variants that {@code isWhitespace}
     * intentionally omits.
     */
    private static boolean isTrimmable(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
