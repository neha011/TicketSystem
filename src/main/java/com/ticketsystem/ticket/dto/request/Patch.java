package com.ticketsystem.ticket.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A field in a PATCH body in one of three states: <em>absent</em> (the client said nothing about it),
 * <em>present and null</em> (the client asked to clear it), or <em>present with a value</em>.
 *
 * <p>This exists because a plain nullable field collapses the first two states into one, and so does
 * {@code Optional<T>}: Jackson's {@code Optional} deserializer yields {@code Optional.empty()} for both a
 * missing property and an explicit {@code null}, so the two are indistinguishable at the DTO. Requirement
 * 4.2 needs them apart — omitting {@code assignee} must leave the current assignee alone, while sending
 * {@code "assignee": null} must clear it. Guessing wrong in either direction silently loses data or
 * silently refuses to release an assignment.
 *
 * <p>Binding is handled by {@link PatchDeserializer}; container-element constraints such as
 * {@code Patch<@Size(max = 100) String>} are handled by {@code PatchValueExtractor}, so a constraint
 * violation is reported against the field's own name ({@code assignee}) rather than a wrapper path.
 *
 * <p>Immutable and safe to share. Instances are compared by state, so an absent patch never equals a
 * present-null one.
 *
 * @param <T> the wrapped value type
 */
@JsonDeserialize(using = PatchDeserializer.class)
public final class Patch<T> {

    private static final Patch<?> ABSENT = new Patch<>(false, null);

    private final boolean present;
    private final T value;

    private Patch(boolean present, T value) {
        this.present = present;
        this.value = value;
    }

    /** The field was not mentioned in the request body: leave the current value untouched. */
    @SuppressWarnings("unchecked")
    public static <T> Patch<T> absent() {
        return (Patch<T>) ABSENT;
    }

    /**
     * The field was supplied. A {@code null} {@code value} is a legitimate, meaningful state here —
     * the client asked to clear the field — which is precisely why this is not {@code Optional}.
     */
    public static <T> Patch<T> of(T value) {
        return new Patch<>(true, value);
    }

    /** @return true when the field appeared in the body at all, whether or not its value was null. */
    public boolean isPresent() {
        return present;
    }

    /** @return true when the field appeared with an explicit null, meaning "clear this value". */
    public boolean isNull() {
        return present && value == null;
    }

    /** @return the supplied value, or null when the field was absent or explicitly null. */
    public T orElseNull() {
        return value;
    }

    /**
     * @return the supplied value
     * @throws IllegalStateException if the field was absent — callers must check {@link #isPresent()}
     *     first, since "absent" carries no value to return
     */
    public T get() {
        if (!present) {
            throw new IllegalStateException("field was absent from the request body");
        }
        return value;
    }

    /**
     * Runs {@code action} only when the field was supplied, passing the new value (possibly null).
     * This is the shape service code wants: "apply exactly the supplied fields" becomes a call per
     * field with no absent/null branching at the call site (Requirement 4.2).
     */
    public void ifPresent(Consumer<T> action) {
        Objects.requireNonNull(action, "action");
        if (present) {
            action.accept(value);
        }
    }

    /**
     * @return the supplied non-null value, or empty when the field was absent <em>or</em> explicitly
     *     null. Use only where those two cases genuinely warrant the same handling — the distinction is
     *     the reason this type exists.
     */
    public Optional<T> toOptional() {
        return Optional.ofNullable(value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Patch<?> that
                && this.present == that.present
                && Objects.equals(this.value, that.value);
    }

    @Override
    public int hashCode() {
        return present ? (31 + Objects.hashCode(value)) : 0;
    }

    @Override
    public String toString() {
        if (!present) {
            return "Patch.absent";
        }
        return "Patch[" + value + "]";
    }
}
