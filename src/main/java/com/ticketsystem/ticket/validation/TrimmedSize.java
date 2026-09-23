package com.ticketsystem.ticket.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Constrains a text value's <em>Trimmed_Length</em> — its length after leading and trailing
 * whitespace has been stripped — to the inclusive range {@code [min, max]}.
 *
 * <p>This constraint exists because {@code @Size} counts raw characters, so {@code @Size(min = 1)}
 * happily accepts {@code "   "}. Every length rule on ticket titles and comment content is specified
 * in trimmed terms, which means a whitespace-only value must count as zero-length and be rejected
 * (Requirements 1.3, 1.4, 4.3, 5.4, 5.5, 10.1, 10.4).
 *
 * <p><strong>The verdict is a function of the raw submitted value.</strong> Trimming happens only to
 * <em>measure</em>; the value itself is never rewritten by validation, and no trimming or sanitizing
 * Jackson deserializer is registered ahead of it. Whatever the client sent is what gets judged, which
 * is what Requirement 5.8 demands. Sanitization, if it is ever introduced, belongs in the service
 * layer after validation has already decided.
 *
 * <p><strong>Null is not this constraint's concern.</strong> Following the Bean Validation
 * convention shared with {@code @Size}, a {@code null} value passes. Presence is a separate rule with
 * a separate client-facing reason ("field is missing" rather than "field is blank"), so pair this
 * annotation with {@code @NotNull} wherever the field is mandatory (Requirements 1.3, 5.3). Leaving
 * null to {@code @NotNull} is also what lets a PATCH body treat an omitted field as "unchanged"
 * rather than as a violation.
 *
 * <p>Length is measured in {@code char} units, matching both {@code @Size} and the {@code VARCHAR}
 * column widths the constraint shadows, so a validated value always fits its column. The whitespace
 * set is wider than {@link String#strip()} — it also covers non-breaking spaces, which
 * {@code strip()} keeps — see {@link TrimmedSizeValidator} for why.
 *
 * <p>Usable on record components, fields, method returns, parameters, and nested type arguments (for
 * example {@code Optional<@TrimmedSize(min = 1, max = 200) String>}).
 */
@Documented
@Constraint(validatedBy = TrimmedSizeValidator.class)
@Target({
    ElementType.FIELD,
    ElementType.METHOD,
    ElementType.PARAMETER,
    ElementType.RECORD_COMPONENT,
    ElementType.TYPE_USE,
    ElementType.ANNOTATION_TYPE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface TrimmedSize {

    /** Smallest permitted trimmed length, inclusive. */
    int min() default 0;

    /** Largest permitted trimmed length, inclusive. */
    int max() default Integer.MAX_VALUE;

    /**
     * Client-facing reason placed in the {@code fieldErrors} entry for the offending field. Phrased
     * for a human reading a form, and it names the trimming rule explicitly so a user who submitted
     * spaces understands why the value counted as empty.
     */
    String message() default "must be {min} to {max} characters after trimming";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
