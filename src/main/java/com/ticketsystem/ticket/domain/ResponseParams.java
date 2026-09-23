package com.ticketsystem.ticket.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Immutable value object holding the parameters that affect an AI-agent response (for example the
 * model identifier) and therefore participate in {@link CacheEntry} identity.
 *
 * <p>Two prompts with identical normalized text but differing response-affecting parameters must
 * derive different cache keys (Requirement 2.4), and two prompts with identical parameters must
 * derive identical keys regardless of the order those parameters were supplied (Requirement 2.3).
 * To make that possible, {@link #canonicalForm()} renders the parameters deterministically by
 * sorting on key, so the same logical parameter set always serializes to the exact same string —
 * this string is what feeds into the canonical identity used for key derivation.
 *
 * <p>The parameters are held in a sorted, defensively-copied immutable map so iteration order (and
 * therefore serialization) is stable. Jackson (de)serializes the object as its underlying map via
 * {@link JsonValue}/{@link JsonCreator}, consistent with the shared {@code ObjectMapper}.
 */
public record ResponseParams(Map<String, String> values) {

    /** A {@code ResponseParams} carrying no parameters. */
    public static final ResponseParams EMPTY = new ResponseParams(Map.of());

    /**
     * Canonicalizes and defensively copies the supplied parameters into a sorted, immutable map.
     *
     * @param values response-affecting parameters keyed by name; {@code null} is treated as empty
     */
    public ResponseParams {
        // Sort by key so serialization is deterministic, and copy so callers cannot mutate our state.
        TreeMap<String, String> sorted = new TreeMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                Objects.requireNonNull(key, "response param key");
                Objects.requireNonNull(value, "response param value");
                sorted.put(key, value);
            });
        }
        values = Map.copyOf(sorted);
    }

    /** Builds a {@code ResponseParams} from a single {@code model} parameter, tolerating a null model. */
    public static ResponseParams ofModel(String model) {
        return model == null ? EMPTY : new ResponseParams(Map.of("model", model));
    }

    /** Reconstructs {@code ResponseParams} from its serialized map form. */
    @JsonCreator
    public static ResponseParams fromValues(Map<String, String> values) {
        return values == null || values.isEmpty() ? EMPTY : new ResponseParams(values);
    }

    /** The sorted, immutable parameter map; also the JSON representation of this value object. */
    @JsonValue
    @Override
    public Map<String, String> values() {
        return values;
    }

    /**
     * Renders these parameters as a deterministic string used as part of a cache entry's canonical
     * identity. Identical parameter sets always produce an identical string (Requirements 2.3, 2.4);
     * the format is {@code key=value} pairs joined by {@code &}, in ascending key order.
     */
    public String canonicalForm() {
        // values is already a sorted (TreeMap-derived) immutable map, so iteration order is stable.
        return values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    /** Alias of {@link #canonicalForm()} kept for callers using the {@code canonicalString} name. */
    public String canonicalString() {
        return canonicalForm();
    }
}
