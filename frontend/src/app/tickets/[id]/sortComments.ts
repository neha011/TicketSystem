import type { CommentResponse } from '@/lib/api/types';

/**
 * Orders comments oldest → newest for display (Requirements 3.6, 5.7).
 *
 * The backend already returns comments ordered by `(createdAt, id)`, but the UI
 * re-sorts defensively so the rendered order is a pure function of the data and
 * never depends on arrival order — including a locally appended comment after a
 * successful submit (Requirement 5.7), which is spliced into the array before
 * this runs.
 *
 * The sort key is `(createdAt, id)`, matching the backend's total order. Sorting
 * by `createdAt` alone is non-deterministic when several comments share a
 * timestamp; the secondary `id` key makes the order total and stable, so equal
 * timestamps never reorder unpredictably between renders. `createdAt` is an
 * ISO-8601 UTC instant, so lexical string comparison is chronological.
 *
 * The input is never mutated: a new array is returned so callers (and React)
 * can rely on referential change signalling a real reorder.
 *
 * @param comments the comments in any order
 * @returns a new array ordered non-decreasing by `(createdAt, id)`
 */
export function sortComments(comments: readonly CommentResponse[]): CommentResponse[] {
  return [...comments].sort((a, b) => {
    if (a.createdAt < b.createdAt) {
      return -1;
    }
    if (a.createdAt > b.createdAt) {
      return 1;
    }
    // Timestamp tie: fall back to the id so the order is total and stable.
    if (a.id < b.id) {
      return -1;
    }
    if (a.id > b.id) {
      return 1;
    }
    return 0;
  });
}
