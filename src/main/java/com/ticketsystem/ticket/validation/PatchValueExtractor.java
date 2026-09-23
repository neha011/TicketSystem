package com.ticketsystem.ticket.validation;

import com.ticketsystem.ticket.dto.request.Patch;
import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.UnwrapByDefault;
import jakarta.validation.valueextraction.ValueExtractor;

/**
 * Teaches Bean Validation to look <em>inside</em> a {@link Patch}, so constraints written on a PATCH
 * field apply to the value the client supplied:
 *
 * <pre>{@code
 * @Size(max = 100) Patch<String> assignee
 * }</pre>
 *
 * <p>Two behaviours follow, and both are the point of the class:
 *
 * <ul>
 *   <li><b>An absent field is never a violation.</b> No value is handed to the receiver, so no constraint
 *       on that field is evaluated at all — "omitted" means "unchanged", not "invalid" (Requirement 4.2).
 *   <li><b>An explicit null is evaluated.</b> {@code null} reaches the constraints, so {@code @Size} and
 *       {@code @TrimmedSize} pass it (clearing a nullable field is legal) while {@code @NotNull} rejects it
 *       (clearing a mandatory field is not).
 * </ul>
 *
 * <p>Because the value is unwrapped, a violation's property path is the field's own name — {@code assignee},
 * not {@code assignee.value} — which is what lets the error response list exactly the offending field names
 * (Requirements 4.10, 10.2).
 *
 * <p>Registered through {@code META-INF/services/jakarta.validation.valueextraction.ValueExtractor} so it
 * applies to every validator factory, including the one the Spring context builds and any plain
 * {@code Validation.buildDefaultValidatorFactory()} used in a test.
 *
 * <p><strong>Requirements: 4.2, 4.3, 4.10, 10.1, 10.2</strong>
 */
@UnwrapByDefault
public class PatchValueExtractor implements ValueExtractor<Patch<@ExtractedValue ?>> {

    @Override
    public void extractValues(Patch<?> originalValue, ValueReceiver receiver) {
        if (originalValue == null || !originalValue.isPresent()) {
            // Nothing extracted: constraints on an omitted field are not evaluated.
            return;
        }
        receiver.value(null, originalValue.orElseNull());
    }
}
