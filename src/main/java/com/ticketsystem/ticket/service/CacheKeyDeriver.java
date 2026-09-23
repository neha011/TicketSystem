package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.domain.ResponseParams;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Derives a deterministic, fixed-length Cache_Key from a normalized prompt and its
 * response-affecting parameters (Requirements 2.4, 2.5).
 *
 * <p>The key is the SHA-256 digest of the prompt's <em>canonical identity</em> — the normalized
 * prompt text joined with {@link ResponseParams}' deterministic sorted-key serialization — rendered
 * as 64 lowercase hexadecimal characters. Because SHA-256 always produces 32 bytes, every key is the
 * same length regardless of how long the input prompt is (Requirement 2.5), and because both the
 * normalization and the parameter serialization are deterministic, identical canonical identities
 * always derive the same key while differing identities derive different keys absent a hash collision
 * (Requirement 2.4).
 *
 * <p>This is a pure, side-effect-free function: it neither reads nor mutates any cache state. Hash
 * collision detection is the responsibility of the caller (the service compares the stored canonical
 * identity of an existing entry against the incoming one, per design), not of this deriver.
 */
public final class CacheKeyDeriver {

    /**
     * Separator between the normalized text and the serialized parameters in the canonical identity.
     *
     * <p>A delimiter that cannot appear in the serialized-parameter key/value form keeps the two
     * segments unambiguous, so {@code ("ab", {}) } and {@code ("a", {b}) } cannot collapse to the same
     * pre-hash input.
     */
    private static final char CANONICAL_SEPARATOR = '|';

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /**
     * Builds the canonical identity for a normalized prompt and its parameters, then derives the
     * Cache_Key from it.
     *
     * @param normalizedText the prompt text after {@link PromptNormalizer} normalization; must not be
     *     null
     * @param params the response-affecting parameters; must not be null
     * @return the Cache_Key as 64 lowercase hex characters
     */
    public String deriveKey(String normalizedText, ResponseParams params) {
        if (normalizedText == null) {
            throw new IllegalArgumentException("normalizedText must not be null");
        }
        if (params == null) {
            throw new IllegalArgumentException("params must not be null");
        }
        return deriveKey(canonicalIdentity(normalizedText, params));
    }

    /**
     * Derives the Cache_Key directly from an already-computed canonical identity string.
     *
     * <p>Exposed so callers that already hold the stored {@code canonicalIdentity} of an entry can
     * derive its key without reconstructing a {@link ResponseParams}.
     *
     * @param canonicalIdentity the canonical identity (normalized text + serialized params); must not
     *     be null
     * @return the Cache_Key as 64 lowercase hex characters
     */
    public String deriveKey(String canonicalIdentity) {
        if (canonicalIdentity == null) {
            throw new IllegalArgumentException("canonicalIdentity must not be null");
        }
        byte[] digest = sha256(canonicalIdentity.getBytes(StandardCharsets.UTF_8));
        return toHex(digest);
    }

    /**
     * Composes the canonical identity for a normalized prompt and its parameters.
     *
     * <p>The format mirrors the {@code canonicalIdentity} stored on a {@code CacheEntry}: the
     * normalized text, a separator, then {@link ResponseParams}' deterministic sorted-key
     * serialization. Keeping this construction in one place guarantees the deriver and the service use
     * an identical pre-hash input.
     *
     * @param normalizedText the normalized prompt text
     * @param params the response-affecting parameters
     * @return the canonical identity string
     */
    public String canonicalIdentity(String normalizedText, ResponseParams params) {
        if (normalizedText == null) {
            throw new IllegalArgumentException("normalizedText must not be null");
        }
        if (params == null) {
            throw new IllegalArgumentException("params must not be null");
        }
        return normalizedText + CANONICAL_SEPARATOR + serializeParams(params);
    }

    /**
     * Deterministically serializes response-affecting parameters as {@code key=value} pairs in
     * ascending key order, joined by {@code &}.
     *
     * <p>Built directly from the parameter map (which {@link ResponseParams} exposes as a stable,
     * sorted, immutable view) so the pre-hash input is deterministic (Requirements 2.3, 2.4)
     * regardless of the order parameters were supplied. Re-sorted here as a defensive measure so the
     * output is independent of the map's own iteration order.
     *
     * @param params the response-affecting parameters
     * @return the deterministic serialized form (empty string when there are no parameters)
     */
    private static String serializeParams(ResponseParams params) {
        Map<String, String> values = params.values();
        return new TreeMap<>(values)
                .entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    private static byte[] sha256(byte[] input) {
        try {
            // SHA-256 is a MessageDigest algorithm every conformant JRE must provide, so this
            // never fails in practice; wrap defensively rather than propagate a checked exception.
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            hex[i * 2] = HEX_DIGITS[b >>> 4];
            hex[i * 2 + 1] = HEX_DIGITS[b & 0x0F];
        }
        return new String(hex);
    }
}
