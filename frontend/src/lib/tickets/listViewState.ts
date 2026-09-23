import type { ApiError } from '@/lib/api/apiClient';
import { errorMapper } from '@/lib/api/errorMapper';
import type { PagedResponse, TicketSummaryResponse } from '@/lib/api/types';

/**
 * The four mutually exclusive states the ticket list view can be in.
 *
 * Modelled as a discriminated union so exactly one is ever rendered: the view
 * switches on `kind` and there is structurally no way to be in two states at
 * once, or in none (Requirements 2.8, 2.9, 6.3). The "Unassigned" placeholder
 * lives only on the `populated` arm, so the error and empty arms cannot carry
 * it (Requirement 2.9).
 */
export type ListViewState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'empty' }
  | { kind: 'populated'; tickets: readonly TicketSummaryResponse[]; page: PageMetadata };

/** The pagination facts a populated view needs to render its controls. */
export interface PageMetadata {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/**
 * The raw query outcome, expressed independently of TanStack Query so the
 * selector is a pure function of plain data and can be exhaustively
 * property-tested without a React tree (see design.md, Property 19).
 *
 * - `pending` — the request is in flight and no prior data is being shown.
 * - `error` — the request failed; carries the normalised {@link ApiError}.
 * - `success` — the request resolved with a page of results.
 */
export type ListQueryResult =
  | { status: 'pending' }
  | { status: 'error'; error: ApiError }
  | { status: 'success'; data: PagedResponse<TicketSummaryResponse> };

/**
 * Collapses a list query outcome to exactly one {@link ListViewState}.
 *
 * The ordering of the branches is the whole point: a request that is still
 * loading is `loading` regardless of any stale data, a failed request is
 * `error` (never "empty", so a failure is never mistaken for "no tickets" —
 * Requirement 2.9), a successful-but-zero-length result is `empty`, and only a
 * successful non-empty result is `populated`. Every input therefore maps to one
 * and only one state.
 *
 * @param result the plain query outcome
 * @returns the single view state to render
 */
export function selectListViewState(result: ListQueryResult): ListViewState {
  switch (result.status) {
    case 'pending':
      return { kind: 'loading' };

    case 'error':
      // A failure is surfaced through the shared status→message mapper so the
      // list view shows the same one message per status as everywhere else, and
      // never leaks payload detail (Requirements 6.3, 11.x).
      return { kind: 'error', message: errorMapper(result.error) };

    case 'success': {
      const { content, page, size, totalElements, totalPages } = result.data;
      if (content.length === 0) {
        // Zero results — whether the repository is empty (Req 2.8) or a
        // keyword/status filter matched nothing (Req 6.3) — is the empty state,
        // not an error.
        return { kind: 'empty' };
      }
      return {
        kind: 'populated',
        tickets: content,
        page: { page, size, totalElements, totalPages },
      };
    }
  }
}

/**
 * The label shown for a ticket's assignee.
 *
 * A ticket is unassigned when its assignee is absent (`null`/`undefined`) or
 * blank — a value that trims to nothing (Requirement 2.7). "Blank" is judged
 * with the same wide whitespace test the backend uses for trimmed-length, so a
 * non-breaking-space-only assignee is treated as unassigned rather than as a
 * one-character name.
 *
 * This lives beside {@link selectListViewState} rather than inside the row
 * component so the "Unassigned exactly when unassigned" property can be checked
 * directly (see design.md, Property 24).
 */
export const UNASSIGNED_LABEL = 'Unassigned';

/** True when an assignee value should render as {@link UNASSIGNED_LABEL}. */
export function isUnassigned(assignee: string | null | undefined): boolean {
  if (assignee === null || assignee === undefined) {
    return true;
  }
  return isBlank(assignee);
}

/** The assignee text to display: the trimmed identifier, or the placeholder. */
export function assigneeDisplay(assignee: string | null | undefined): string {
  return isUnassigned(assignee) ? UNASSIGNED_LABEL : (assignee as string).trim();
}

/**
 * True when every code point in `value` is whitespace (or the string is empty).
 *
 * Mirrors the backend's Trimmed_Length whitespace set (`isWhitespace` widened
 * to cover NBSP U+00A0 and other space-separator code points) so the UI's
 * notion of "blank" cannot diverge from the server's.
 */
function isBlank(value: string): boolean {
  for (const codePoint of value) {
    const cp = codePoint.codePointAt(0);
    if (cp === undefined) {
      continue;
    }
    if (!isWhitespaceCodePoint(cp)) {
      return false;
    }
  }
  return true;
}

/** ASCII/Unicode whitespace plus the space-separator category (incl. NBSP). */
function isWhitespaceCodePoint(cp: number): boolean {
  // Common ASCII whitespace and control whitespace.
  if (cp === 0x09 || cp === 0x0a || cp === 0x0b || cp === 0x0c || cp === 0x0d || cp === 0x20) {
    return true;
  }
  // Next line, NBSP.
  if (cp === 0x85 || cp === 0xa0) {
    return true;
  }
  // Ogham space mark, en/em spaces range, line/paragraph separators, narrow
  // NBSP, medium mathematical space, ideographic space, and the general
  // space-separator block.
  if (cp === 0x1680) return true;
  if (cp >= 0x2000 && cp <= 0x200a) return true;
  if (cp === 0x2028 || cp === 0x2029) return true;
  if (cp === 0x202f || cp === 0x205f || cp === 0x3000) return true;
  return false;
}

/** The inclusive keyword length bounds the backend enforces (Req 6.1, 6.4). */
export const KEYWORD_MIN_LENGTH = 1;
export const KEYWORD_MAX_LENGTH = 200;

/**
 * The message shown when a keyword search term is outside 1–200 characters.
 *
 * The UI validates for responsiveness only; the backend remains the authority
 * (Requirement 10.1). This is the copy Requirement 6.4 requires — it states the
 * valid length range so the user knows how to correct the term.
 */
export const KEYWORD_LENGTH_MESSAGE = `Search term must be between ${KEYWORD_MIN_LENGTH} and ${KEYWORD_MAX_LENGTH} characters.`;

/**
 * Validates a raw keyword input for length.
 *
 * An absent or empty term means "no keyword filter" and is valid (the search is
 * simply unfiltered). A present term is length-checked against the same 1–200
 * bounds the backend enforces; the length is measured on the raw string so it
 * matches the server's `keyword` query-parameter rule.
 *
 * @param raw the current search-box value
 * @returns `null` when acceptable, else the {@link KEYWORD_LENGTH_MESSAGE}
 */
export function validateKeyword(raw: string): string | null {
  if (raw.length === 0) {
    return null;
  }
  if (raw.length < KEYWORD_MIN_LENGTH || raw.length > KEYWORD_MAX_LENGTH) {
    return KEYWORD_LENGTH_MESSAGE;
  }
  return null;
}
