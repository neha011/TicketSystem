package com.ticketsystem.ticket.service;

import com.ticketsystem.ticket.validation.TrimmedSizeValidator;

/**
 * Normalizes raw prompt text into a canonical form prior to Cache_Key derivation.
 *
 * <p>Normalization is a pure, deterministic function: it trims leading and trailing whitespace and
 * collapses every internal run of whitespace into a single ASCII space ({@code U+0020}). The same
 * raw input therefore always maps to the same normalized output, which is what makes key derivation
 * over the result deterministic (Requirements 2.1, 2.2).
 *
 * <p>The whitespace definition is deliberately shared with {@link TrimmedSizeValidator}: a character
 * is whitespace here when it is {@code isWhitespace} <em>or</em> {@code isSpaceChar}. That set is
 * wider than {@link String#strip()} — it additionally covers the non-breaking variants (NBSP
 * {@code U+00A0}, narrow NBSP {@code U+202F}, figure space {@code U+2007}) that {@code isWhitespace}
 * intentionally omits. Reusing one definition keeps the "what counts as whitespace" rule consistent
 * across validation and cache normalization, so two prompts the validator treats as equivalently
 * padded also normalize to the same canonical text.
 */
public final class PromptNormalizer {

    /**
     * Trim leading and trailing whitespace and collapse each internal whitespace run to a single
     * space.
     *
     * <p>Iteration is by code point so a supplementary character (e.g. an emoji) is never split, and
     * the shared trimmable definition from {@link TrimmedSizeValidator} decides what is whitespace.
     * A raw prompt that is {@code null} or entirely whitespace normalizes to the empty string.
     *
     * @param rawPrompt the raw prompt text as submitted
     * @return the canonical normalized text (never {@code null})
     */
    public String normalize(String rawPrompt) {
        if (rawPrompt == null) {
            return "";
        }

        StringBuilder normalized = new StringBuilder(rawPrompt.length());
        int length = rawPrompt.length();
        boolean pendingSpace = false;
        int index = 0;

        while (index < length) {
            int codePoint = rawPrompt.codePointAt(index);
            int charCount = Character.charCount(codePoint);

            if (isTrimmable(codePoint)) {
                // Defer emitting a separator: leading/trailing runs vanish, internal runs collapse to
                // a single space that is only written once a non-whitespace code point follows.
                if (normalized.length() > 0) {
                    pendingSpace = true;
                }
            } else {
                if (pendingSpace) {
                    normalized.append(' ');
                    pendingSpace = false;
                }
                normalized.appendCodePoint(codePoint);
            }

            index += charCount;
        }

        return normalized.toString();
    }

    /**
     * A code point is whitespace for normalization when it is {@code isWhitespace} or
     * {@code isSpaceChar}, matching {@link TrimmedSizeValidator}'s definition exactly so the two
     * components never disagree about what counts as whitespace.
     */
    private static boolean isTrimmable(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
