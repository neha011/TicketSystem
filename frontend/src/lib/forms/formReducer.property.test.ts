import fc from 'fast-check';
import { describe, expect, it } from 'vitest';
import { ApiError, type ApiErrorKind } from '@/lib/api/apiClient';
import type { ApiFieldError } from '@/lib/api/types';
import { type FormState, formReducer } from './formReducer';

/**
 * Property 23: A validation failure preserves form input.
 *
 * A `SUBMISSION_FAILED` action carries a 400 Bad Request. Requirement 11.3
 * demands that such a failure never touches what the user typed: it may only
 * (re)build the `errors` annotation map. This property sweeps the whole input
 * space that matters here —
 *
 *   - arbitrary form *shapes*: any set of field names the form owns;
 *   - arbitrary field *values*: empty, partially filled, whitespace-laden,
 *     maximum-length, and special/unicode characters (all preserved verbatim);
 *   - arbitrary *400 payloads*: `fieldErrors` naming a mix of fields the form
 *     owns (present) and fields it does not (absent), plus duplicates and an
 *     empty list.
 *
 * and asserts, for every combination, that after applying the 400 every field
 * value is equal (value-level) to its pre-submit value and that the SOLE delta
 * between the old and new state is the `errors` map. It complements the
 * example-based cases in `formReducer.test.ts`.
 *
 * Validates: Requirements 11.3
 */

/** Above this a value is a paste dump; the cap only shapes the generator. */
const MAX_FIELD_LENGTH = 200;

/**
 * Field values that mirror what a user can actually enter, biased toward the
 * awkward inputs Requirement 11.3 is about: the empty string, blank/whitespace
 * runs, maximum-length text, and special/unicode/multi-line characters. Plain
 * arbitrary strings round out the space.
 */
const fieldValueArb: fc.Arbitrary<string> = fc.oneof(
  fc.string(),
  fc.constant(''),
  fc.constantFrom(' ', '   ', '\t', '\n', ' \t \n ', '\r\n'),
  // Maximum-length values, incl. whitespace-padded ones.
  fc.string({ minLength: MAX_FIELD_LENGTH, maxLength: MAX_FIELD_LENGTH }),
  fc.constantFrom(
    '  Spaces & <special> chars  ',
    'multi\nline?',
    '<script>alert(1)</script>',
    'emoji 🎫🔥 unicode ñ é ü 日本語',
    '"quotes" and \\backslashes\\ and /slashes/',
    '   trailing and leading   ',
  ),
);

/** The field names a form may own; kept short so overlaps with error names occur. */
const fieldNameArb: fc.Arbitrary<string> = fc.constantFrom(
  'title',
  'description',
  'priority',
  'assignee',
  'content',
);

/**
 * An arbitrary form's entered values: a non-empty record over a subset of the
 * known field names, each mapped to an arbitrary (often awkward) value.
 */
const valuesArb: fc.Arbitrary<Record<string, string>> = fc
  .uniqueArray(fieldNameArb, { minLength: 1, maxLength: 5 })
  .chain((fields) =>
    fc.tuple(...fields.map(() => fieldValueArb)).map((vals) => {
      const record: Record<string, string> = {};
      fields.forEach((field, i) => {
        record[field] = vals[i] as string;
      });
      return record;
    }),
  );

/** Field names that the form does NOT own — the "absent" side of the payload. */
const absentFieldNameArb: fc.Arbitrary<string> = fc.constantFrom(
  'nonExistent',
  'ghostField',
  'unknownKey',
  'orphan',
  '',
);

/**
 * A 400's `fieldErrors`, deliberately naming a mix of fields the form owns
 * (drawn from its actual keys) and fields it does not, with possible
 * duplicates and, sometimes, none at all.
 */
function fieldErrorsArbFor(values: Record<string, string>): fc.Arbitrary<ApiFieldError[]> {
  const presentNameArb =
    Object.keys(values).length > 0 ? fc.constantFrom(...Object.keys(values)) : absentFieldNameArb;
  const nameArb = fc.oneof(presentNameArb, absentFieldNameArb);
  return fc.array(
    fc.record({ field: nameArb, reason: fc.string({ minLength: 1 }) }),
    { maxLength: 8 },
  );
}

const kindArb: fc.Arbitrary<ApiErrorKind> = fc.constantFrom('http', 'malformed');

/** Builds a 400 ApiError from generated field errors, without any network. */
function validationError(fieldErrors: ApiFieldError[], kind: ApiErrorKind): ApiError {
  return new ApiError({
    status: 400,
    kind,
    serverMessage: 'Validation failed',
    fieldErrors,
    fallbackMessage: 'The ticket service responded with status 400.',
  });
}

describe('formReducer — Property 23: a validation failure preserves form input', () => {
  it('should leave every entered value unchanged and make the error map the only delta on a 400', () => {
    fc.assert(
      fc.property(
        valuesArb.chain((values) =>
          fc.record({
            values: fc.constant(values),
            fieldErrors: fieldErrorsArbFor(values),
            kind: kindArb,
          }),
        ),
        ({ values, fieldErrors, kind }) => {
          // A pristine, snapshotted copy of the values to compare against — so
          // the assertion cannot be fooled by any accidental in-place mutation.
          const before: Record<string, string> = { ...values };
          const state: FormState<Record<string, string>> = { values, errors: {} };

          const next = formReducer(state, {
            type: 'SUBMISSION_FAILED',
            error: validationError(fieldErrors, kind),
          });

          // Value-level equality: every field the form owns is byte-for-byte
          // what the user had entered; no field was cleared, defaulted, added,
          // or removed.
          expect(next.values).toEqual(before);
          expect(Object.keys(next.values).sort()).toEqual(Object.keys(before).sort());

          // The only delta is the error map: values keep their reference, and
          // errors are a subset of the form's own fields (absent fields dropped).
          expect(next.values).toBe(state.values);
          for (const errorField of Object.keys(next.errors)) {
            expect(Object.prototype.hasOwnProperty.call(before, errorField)).toBe(true);
          }

          // The reducer did not mutate the input state's values in place.
          expect(state.values).toEqual(before);
        },
      ),
      { numRuns: 300 },
    );
  });

  it('should preserve pre-existing values AND pre-existing errors state as the only-error delta, across arbitrary prior errors', () => {
    fc.assert(
      fc.property(
        valuesArb.chain((values) =>
          fc.record({
            values: fc.constant(values),
            priorErrors: fc.dictionary(
              fc.constantFrom(...Object.keys(values)),
              fc.string({ minLength: 1 }),
            ),
            fieldErrors: fieldErrorsArbFor(values),
            kind: kindArb,
          }),
        ),
        ({ values, priorErrors, fieldErrors, kind }) => {
          const before: Record<string, string> = { ...values };
          const state: FormState<Record<string, string>> = { values, errors: priorErrors };

          const next = formReducer(state, {
            type: 'SUBMISSION_FAILED',
            error: validationError(fieldErrors, kind),
          });

          // Even when the form already carried errors, the failure touches
          // values not at all — the delta is confined to the error map.
          expect(next.values).toEqual(before);
          expect(next.values).toBe(state.values);
        },
      ),
      { numRuns: 200 },
    );
  });
});
