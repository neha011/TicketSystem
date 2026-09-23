import fc from 'fast-check';
import { describe, expect, it } from 'vitest';
import { UNASSIGNED_LABEL, assigneeDisplay, isUnassigned } from './listViewState';

/**
 * Property 24: Assignee display shows "Unassigned" exactly when unassigned.
 *
 * Requirement 2.7 says the UI shows an "Unassigned" indication in place of an
 * assignee if and only if the ticket has no assignee — where "no assignee"
 * covers an absent value (`null`/`undefined`) and a blank one (a string that is
 * whitespace-only under the wide whitespace set the backend uses). This asserts
 * the biconditional directly against an independent oracle, sweeping null,
 * undefined, empty, whitespace-only (including NBSP U+00A0, ideographic space
 * U+3000, and other space-separator code points), and genuine non-blank names.
 *
 * Validates: Requirements 2.7
 */

/**
 * The wide whitespace set the module treats as blank. Mirrors the code points
 * enumerated by `isWhitespaceCodePoint` in listViewState.ts so the generator
 * and the code under test share the same notion of "blank". Kept as a local
 * oracle rather than imported, because the module intentionally does not export
 * its whitespace predicate.
 */
const WHITESPACE_CODE_POINTS = [
  0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x20, // ASCII/control whitespace + space
  0x85, 0xa0, // NEL, NBSP
  0x1680, // Ogham space mark
  0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a, // en/em spaces range
  0x2028, 0x2029, // line/paragraph separators
  0x202f, 0x205f, 0x3000, // narrow NBSP, medium math space, ideographic space
];

const whitespaceChars = WHITESPACE_CODE_POINTS.map((cp) => String.fromCodePoint(cp));

/** A string composed entirely of whitespace code points (length 0..20). */
const whitespaceOnlyArb: fc.Arbitrary<string> = fc
  .array(fc.constantFrom(...whitespaceChars), { minLength: 0, maxLength: 20 })
  .map((chars) => chars.join(''));

/**
 * A genuinely non-blank assignee: at least one non-whitespace code point,
 * possibly surrounded by whitespace. Reject the (rare) case where fast-check's
 * "non-whitespace" core happens to trim away to nothing under our wider set.
 */
const nonBlankAssigneeArb: fc.Arbitrary<string> = fc
  .tuple(whitespaceOnlyArb, fc.string({ minLength: 1 }), whitespaceOnlyArb)
  .map(([lead, core, trail]) => `${lead}${core}${trail}`)
  .filter((value) => !isWhitespaceOnly(value));

/** Every input variant the task requires, tagged with the expected verdict. */
const assigneeArb: fc.Arbitrary<{ value: string | null | undefined; expectUnassigned: boolean }> = fc.oneof(
  fc.constant({ value: null as string | null | undefined, expectUnassigned: true }),
  fc.constant({ value: undefined as string | null | undefined, expectUnassigned: true }),
  fc.constant({ value: '' as string | null | undefined, expectUnassigned: true }),
  whitespaceOnlyArb.map((value) => ({ value: value as string | null | undefined, expectUnassigned: true })),
  nonBlankAssigneeArb.map((value) => ({ value: value as string | null | undefined, expectUnassigned: false })),
);

/** Independent oracle for "blank" — every code point is in the whitespace set. */
function isWhitespaceOnly(value: string): boolean {
  for (const ch of value) {
    if (!whitespaceChars.includes(ch)) {
      return false;
    }
  }
  return true;
}

describe('assignee placeholder — Property 24: "Unassigned" exactly when unassigned', () => {
  it('should treat a value as unassigned if and only if it is absent or blank', () => {
    fc.assert(
      fc.property(assigneeArb, ({ value, expectUnassigned }) => {
        expect(isUnassigned(value)).toBe(expectUnassigned);
      }),
      { numRuns: 500 },
    );
  });

  it('should display the placeholder if and only if the value is absent or blank', () => {
    fc.assert(
      fc.property(assigneeArb, ({ value, expectUnassigned }) => {
        const display = assigneeDisplay(value);
        const showsPlaceholder = display === UNASSIGNED_LABEL;

        // The biconditional: placeholder shown <=> value absent or blank.
        expect(showsPlaceholder).toBe(expectUnassigned);

        // When not the placeholder, the display is the trimmed non-blank name.
        if (!expectUnassigned) {
          expect(display).toBe((value as string).trim());
          expect(display.length).toBeGreaterThan(0);
        }
      }),
      { numRuns: 500 },
    );
  });
});
