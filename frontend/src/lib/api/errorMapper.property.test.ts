import fc from 'fast-check';
import { describe, expect, it } from 'vitest';
import { ApiError, type ApiErrorKind } from './apiClient';
import { ERROR_MESSAGES, errorMapper } from './errorMapper';

/**
 * Property 21: A failed request produces exactly one status-specific message.
 *
 * For every failure the client can surface — the full HTTP status set plus a
 * network failure — the mapper must return exactly one non-empty message, and
 * that message must be the one its status designates. The generator sweeps the
 * RAW backend message variants (arbitrary, empty, blank/whitespace, and absent)
 * and pushes each through the client's normalisation boundary before the mapper
 * sees it, so the "exactly one, correct one" guarantee is exercised across the
 * whole input space exactly as production would present it — not just the happy
 * path, and never through the unreachable "blank serverMessage" state.
 *
 * Validates: Requirements 11.4, 11.5, 11.6, 11.7, 11.9
 */

/** The status set the mapper distinguishes, plus representative unmapped codes. */
const STATUS_SET = [400, 401, 403, 404, 409, 422, 500, 502, 503] as const;

/**
 * Independent oracle: the single message a status must yield, derived straight
 * from Requirement 11 rather than from the mapper's own code. `serverMessage`
 * is only honoured on the 400/409 arms and only when the client already deemed
 * it a safe, non-null summary.
 */
function expectedMessage(status: number | null, isNetwork: boolean, serverMessage: string | null): string {
  if (isNetwork || status === null) {
    return ERROR_MESSAGES.generic;
  }
  switch (status) {
    case 400:
      return serverMessage ?? ERROR_MESSAGES.genericValidation;
    case 401:
      return ERROR_MESSAGES.sessionExpired;
    case 403:
      return ERROR_MESSAGES.notPermitted;
    case 404:
      return ERROR_MESSAGES.ticketNotFound;
    case 409:
      return serverMessage ?? ERROR_MESSAGES.statusChangeNotAllowed;
    default:
      return ERROR_MESSAGES.generic;
  }
}

/** The distinct, non-empty messages the mapper is allowed to emit. */
const KNOWN_MESSAGES = new Set<string>(Object.values(ERROR_MESSAGES));

/** Above this length a backend message is a payload dump, not a summary. */
const MAX_SERVER_TEXT_LENGTH = 500;

/**
 * Mirrors the private `safeText` boundary in apiClient.ts. That function is the
 * ONLY producer of `ApiError.serverMessage`: it trims the raw backend text and
 * yields `null` for empty, whitespace-only, multi-line, or over-length values,
 * else the trimmed single-line summary. It is not exported, so the contract is
 * replicated here rather than imported — keep the two in lockstep.
 *
 * The consequence the generator must honour: `serverMessage` is NEVER an empty
 * or blank string in production. It is always either `null` or a genuine
 * non-empty, trimmed, single-line summary. Feeding a raw empty/blank string in
 * as `serverMessage` would exercise an unreachable state.
 */
function normaliseServerMessage(raw: string | undefined): string | null {
  if (typeof raw !== 'string') {
    return null;
  }
  const trimmed = raw.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_SERVER_TEXT_LENGTH) {
    return null;
  }
  if (/[\r\n]/.test(trimmed)) {
    return null;
  }
  return trimmed;
}

/**
 * RAW backend `message` variants the task requires us to cover: arbitrary
 * strings, the empty string, blank/whitespace-only strings, and absent
 * (`undefined`). These are what a real backend body could carry — they are fed
 * through {@link normaliseServerMessage} (the apiClient boundary) before ever
 * reaching the mapper, exactly as production does.
 */
const rawServerMessageArb: fc.Arbitrary<string | undefined> = fc.oneof(
  fc.string(),
  fc.constant(''),
  fc.constantFrom(' ', '   ', '\t', '\n', ' \t \n '),
  fc.constant(undefined),
);

const kindArb: fc.Arbitrary<ApiErrorKind> = fc.constantFrom('http', 'malformed');

describe('errorMapper — Property 21: exactly one status-specific message', () => {
  it('should return exactly one message equal to the one mapped to the received status', () => {
    fc.assert(
      fc.property(
        fc.constantFrom(...STATUS_SET),
        rawServerMessageArb,
        kindArb,
        (status, rawServerMessage, kind) => {
          // The client normalises the raw backend message at the boundary; the
          // mapper only ever sees the result. Empty/blank/absent -> null.
          const serverMessage = normaliseServerMessage(rawServerMessage);

          const error = new ApiError({
            status,
            kind,
            serverMessage,
            fieldErrors: [],
            fallbackMessage: `The ticket service responded with status ${status}.`,
          });

          const message = errorMapper(error);

          // Exactly one message: a single non-empty string is returned.
          expect(typeof message).toBe('string');
          expect(message.length).toBeGreaterThan(0);

          // It is the message the received status designates.
          expect(message).toBe(expectedMessage(status, false, serverMessage));
        },
      ),
      { numRuns: 200 },
    );
  });

  it('should return exactly the generic message for a network failure regardless of message variant', () => {
    fc.assert(
      fc.property(rawServerMessageArb, (rawServerMessage) => {
        const serverMessage = normaliseServerMessage(rawServerMessage);
        const error = new ApiError({
          status: null,
          kind: 'network',
          serverMessage,
          fieldErrors: [],
          fallbackMessage: 'The request did not reach the ticket service.',
        });

        const message = errorMapper(error);

        expect(message).toBe(ERROR_MESSAGES.generic);
        expect(message.length).toBeGreaterThan(0);
      }),
      { numRuns: 100 },
    );
  });

  it('should only ever emit one of the known status messages (or a passed-through backend summary)', () => {
    fc.assert(
      fc.property(
        fc.constantFrom(...STATUS_SET),
        rawServerMessageArb,
        kindArb,
        (status, rawServerMessage, kind) => {
          const serverMessage = normaliseServerMessage(rawServerMessage);
          const error = new ApiError({
            status,
            kind,
            serverMessage,
            fieldErrors: [],
            fallbackMessage: `status ${status}`,
          });

          const message = errorMapper(error);

          // The result is either a fixed known message, or — only on the
          // 400/409 arms — the exact backend summary passed through verbatim.
          const isKnown = KNOWN_MESSAGES.has(message);
          const isPassThrough =
            (status === 400 || status === 409) && serverMessage !== null && message === serverMessage;

          expect(isKnown || isPassThrough).toBe(true);
        },
      ),
      { numRuns: 200 },
    );
  });
});
