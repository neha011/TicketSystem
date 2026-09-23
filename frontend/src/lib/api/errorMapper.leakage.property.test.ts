import fc from 'fast-check';
import { describe, expect, it } from 'vitest';
import { ApiError } from './apiClient';
import { ERROR_MESSAGES, errorMapper } from './errorMapper';

/**
 * Property 22: Generic failures never leak internal detail.
 *
 * For every 500-class server fault and every network failure, the mapper must
 * return the single fixed generic message verbatim — and that output must
 * contain no fragment of the failure payload. The generator builds adversarial
 * payloads out of the shapes a Spring Boot backend actually emits when it
 * spills internals: Java stack-trace frames, JPQL fragments, exception class
 * names, and absolute file paths. The assertion is two-sided: the output equals
 * the fixed constant, AND no meaningful token from the payload survives into
 * it. This proves the 500/network arms are structurally content-free rather
 * than merely returning a benign default on the inputs a unit test happened to
 * pick.
 *
 * Validates: Requirements 11.8
 */

/** Above this length / on any newline the client discards the backend text. */
const MAX_SERVER_TEXT_LENGTH = 500;

/**
 * Mirrors the private `safeText` boundary in apiClient.ts — the ONLY producer
 * of `ApiError.serverMessage`. It trims, and yields `null` for empty,
 * whitespace-only, multi-line, or over-length text; otherwise the trimmed,
 * single-line summary. Replicated here (not exported) so the adversarial
 * payloads reach the mapper exactly as production would present them: most
 * stack-trace / JPQL dumps are multi-line or over-length and collapse to
 * `null`, while a short single-line fragment could survive — and must STILL
 * not leak, because the 500/network arms ignore `serverMessage` entirely.
 */
function normaliseServerMessage(raw: string): string | null {
  const trimmed = raw.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_SERVER_TEXT_LENGTH) {
    return null;
  }
  if (/[\r\n]/.test(trimmed)) {
    return null;
  }
  return trimmed;
}

/** Java package/class identifiers used to assemble exception names and frames. */
const javaIdentArb: fc.Arbitrary<string> = fc
  .stringMatching(/^[A-Za-z][A-Za-z0-9]{0,11}$/)
  .filter((s) => s.length > 0);

/** A dotted, fully-qualified name, e.g. `com.ticketsystem.ticket.TicketService`. */
const qualifiedNameArb: fc.Arbitrary<string> = fc
  .array(javaIdentArb, { minLength: 2, maxLength: 6 })
  .map((parts) => parts.join('.'));

/** `org.springframework.dao.DataIntegrityViolationException` and friends. */
const exceptionClassArb: fc.Arbitrary<string> = fc
  .tuple(qualifiedNameArb, javaIdentArb)
  .map(([pkg, name]) => `${pkg}.${name}Exception`);

/** `at com.ticketsystem.ticket.service.TicketServiceImpl.create(TicketServiceImpl.java:42)`. */
const stackFrameArb: fc.Arbitrary<string> = fc
  .tuple(qualifiedNameArb, javaIdentArb, javaIdentArb, fc.integer({ min: 1, max: 9999 }))
  .map(([cls, method, file, line]) => `at ${cls}.${method}(${file}.java:${line})`);

/** `select t from Ticket t where t.status = :status`. */
const jpqlFragmentArb: fc.Arbitrary<string> = fc
  .tuple(javaIdentArb, javaIdentArb, javaIdentArb)
  .map(([entity, alias, field]) => {
    const a = alias.toLowerCase();
    return `select ${a} from ${entity} ${a} where ${a}.${field} = :${field}`;
  });

/** `/home/app/src/main/java/com/ticketsystem/ticket/Foo.java`. */
const absolutePathArb: fc.Arbitrary<string> = fc
  .array(javaIdentArb, { minLength: 2, maxLength: 7 })
  .chain((segments) =>
    javaIdentArb.map((file) => `/${segments.join('/')}/${file}.java`),
  );

/** One adversarial fragment drawn from any of the four internal-detail shapes. */
const adversarialFragmentArb: fc.Arbitrary<string> = fc.oneof(
  exceptionClassArb,
  stackFrameArb,
  jpqlFragmentArb,
  absolutePathArb,
);

/**
 * A full adversarial payload: one or more fragments joined as a backend body
 * would join them — with newlines (a real stack trace) or with spaces (a
 * flattened single-line dump). Keeping both shapes ensures we exercise the
 * common multi-line case (collapses to `null` at the boundary) and the
 * dangerous single-line case (could survive into `serverMessage`).
 */
const adversarialPayloadArb: fc.Arbitrary<string> = fc
  .array(adversarialFragmentArb, { minLength: 1, maxLength: 6 })
  .chain((fragments) =>
    fc.constantFrom('\n', ' ', '\n\tat ', '; ').map((sep) => fragments.join(sep)),
  );

/** 5xx codes plus `null` (network). Every one must yield the generic message. */
const failureStatusArb: fc.Arbitrary<number | null> = fc.constantFrom(
  500,
  501,
  502,
  503,
  504,
  null,
);

/**
 * Splits an adversarial payload into the tokens that would betray internals if
 * any leaked: dotted class names, method/file identifiers, path segments, JPQL
 * keywords. We deliberately keep only tokens of length ≥ 3 so we do not chase
 * incidental single letters that also occur inside the fixed generic English
 * sentence (e.g. "a", "in"). Every retained token must be absent from output.
 */
function meaningfulTokens(payload: string): string[] {
  return payload
    .split(/[\s(),:;]+/)
    .map((t) => t.trim())
    .filter((t) => t.length >= 3);
}

describe('errorMapper — Property 22: generic failures never leak internal detail', () => {
  it('returns exactly the fixed generic message and leaks no payload token, for every 500/network failure', () => {
    fc.assert(
      fc.property(
        failureStatusArb,
        adversarialPayloadArb,
        (status, payload) => {
          // Route the raw adversarial payload through the same boundary the
          // client applies before the mapper can ever see it.
          const serverMessage = normaliseServerMessage(payload);

          const error = new ApiError({
            status,
            kind: status === null ? 'network' : 'http',
            serverMessage,
            fieldErrors: [],
            fallbackMessage:
              status === null
                ? 'The request did not reach the ticket service.'
                : `The ticket service responded with status ${status}.`,
          });

          const message = errorMapper(error);

          // Two-sided assertion. First: the output is the fixed constant, exactly.
          expect(message).toBe(ERROR_MESSAGES.generic);

          // Second: no meaningful token from the adversarial payload appears in
          // the output — nothing internal leaked through.
          for (const token of meaningfulTokens(payload)) {
            expect(message).not.toContain(token);
          }
        },
      ),
      { numRuns: 300 },
    );
  });

  it('ignores even a short single-line adversarial fragment that survives into serverMessage', () => {
    // Worst case: a fragment short and single-line enough to pass the client
    // boundary as a "summary". The 500/network arms must still discard it.
    fc.assert(
      fc.property(
        failureStatusArb,
        adversarialFragmentArb.filter(
          (f) => f.trim().length > 0 && f.trim().length <= MAX_SERVER_TEXT_LENGTH && !/[\r\n]/.test(f),
        ),
        (status, fragment) => {
          const serverMessage = fragment.trim();

          const error = new ApiError({
            status,
            kind: status === null ? 'network' : 'http',
            serverMessage,
            fieldErrors: [],
            fallbackMessage: 'ignored',
          });

          const message = errorMapper(error);

          expect(message).toBe(ERROR_MESSAGES.generic);
          for (const token of meaningfulTokens(fragment)) {
            expect(message).not.toContain(token);
          }
        },
      ),
      { numRuns: 300 },
    );
  });
});
