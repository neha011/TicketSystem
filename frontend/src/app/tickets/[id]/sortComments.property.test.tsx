import { cleanup, render, screen, within } from '@testing-library/react';
import fc from 'fast-check';
import { afterEach, describe, expect, it } from 'vitest';
import type { CommentResponse } from '@/lib/api/types';
import { CommentList } from './CommentList';
import { sortComments } from './sortComments';

/**
 * Property 25: Comments render oldest to newest.
 *
 * For all comment collections in any input order (including duplicate
 * `createdAt` values), the rendered sequence is non-decreasing by
 * `(createdAt, id)` and is a permutation of the input — no comment dropped,
 * none duplicated. The no-comments indication is shown if and only if the
 * collection is empty.
 *
 * These properties target the pure {@link sortComments}, which `CommentList`
 * uses verbatim to produce render order; the empty-state arm mirrors the
 * component's `length === 0` branch.
 *
 * Validates: Requirements 3.6, 3.7, 3.8, 5.7
 */

/**
 * A small pool of ISO-8601 instants so `createdAt` ties are frequent — ordering
 * by timestamp alone is non-deterministic under ties, so this stresses the
 * `(createdAt, id)` total order the property depends on.
 */
const CREATED_AT_POOL = [
  '2026-09-05T10:15:30Z',
  '2026-09-05T10:15:31Z',
  '2026-09-05T10:16:00Z',
  '2026-09-06T00:00:00Z',
] as const;

/** Generates a single comment with a unique id and a tie-prone timestamp. */
function commentArb(): fc.Arbitrary<CommentResponse> {
  return fc.record({
    id: fc.uuid(),
    author: fc.string(),
    content: fc.string(),
    createdAt: fc.constantFrom(...CREATED_AT_POOL),
  });
}

/**
 * Generates 0..50 comments with distinct ids, in randomized order. Distinct ids
 * keep the sort key `(createdAt, id)` a strict total order, matching the
 * backend contract, and let permutation be checked by id multiset.
 */
function commentsArb(): fc.Arbitrary<CommentResponse[]> {
  return fc
    .uniqueArray(fc.uuid(), { minLength: 0, maxLength: 50 })
    .chain((ids) =>
      fc.tuple(
        ...ids.map((id) =>
          fc.record({
            id: fc.constant(id),
            author: fc.string(),
            content: fc.string(),
            createdAt: fc.constantFrom(...CREATED_AT_POOL),
          }),
        ),
      ),
    )
    .map((comments) => comments as CommentResponse[]);
}

/** Compares two comments by the `(createdAt, id)` total order. */
function keyCompare(a: CommentResponse, b: CommentResponse): number {
  if (a.createdAt !== b.createdAt) {
    return a.createdAt < b.createdAt ? -1 : 1;
  }
  if (a.id !== b.id) {
    return a.id < b.id ? -1 : 1;
  }
  return 0;
}

afterEach(() => {
  cleanup();
});

describe('sortComments — Property 25: comments render oldest to newest', () => {
  it('produces a sequence non-decreasing by (createdAt, id)', () => {
    fc.assert(
      fc.property(commentsArb(), (comments) => {
        const ordered = sortComments(comments);
        for (let i = 1; i < ordered.length; i += 1) {
          const prev = ordered[i - 1]!;
          const curr = ordered[i]!;
          expect(keyCompare(prev, curr)).toBeLessThanOrEqual(0);
        }
      }),
      { numRuns: 100 },
    );
  });

  it('is a permutation of the input — same multiset, nothing dropped or duplicated', () => {
    fc.assert(
      fc.property(commentsArb(), (comments) => {
        const ordered = sortComments(comments);
        expect(ordered).toHaveLength(comments.length);
        // Compare by id multiset (ids are unique here, so a sorted-id equality suffices).
        const inputIds = comments.map((c) => c.id).sort();
        const outputIds = ordered.map((c) => c.id).sort();
        expect(outputIds).toEqual(inputIds);
      }),
      { numRuns: 100 },
    );
  });

  it('does not mutate the input array', () => {
    fc.assert(
      fc.property(commentsArb(), (comments) => {
        const snapshot = [...comments];
        sortComments(comments);
        expect(comments).toEqual(snapshot);
      }),
      { numRuns: 100 },
    );
  });

  it('CommentList shows the no-comments indication if and only if the collection is empty', () => {
    fc.assert(
      fc.property(commentsArb(), (comments) => {
        cleanup();
        render(<CommentList comments={comments} />);

        const noComments = screen.queryByTestId('no-comments');
        const list = screen.queryByRole('list', { name: 'Comments' });

        if (comments.length === 0) {
          // Empty: indication present, list absent.
          expect(noComments).not.toBeNull();
          expect(list).toBeNull();
        } else {
          // Non-empty: list present, indication absent — never both.
          expect(noComments).toBeNull();
          expect(list).not.toBeNull();
          // Exactly one rendered item per input comment (permutation, nothing dropped).
          expect(within(list as HTMLElement).getAllByRole('listitem')).toHaveLength(comments.length);
        }
      }),
      { numRuns: 100 },
    );
  });
});
