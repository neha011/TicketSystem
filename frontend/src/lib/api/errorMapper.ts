import type { ApiError } from './apiClient';

/**
 * The fixed, content-free copy for failures the user can neither diagnose nor
 * act on from the message alone. Built as constants so the generic 500/network
 * text can never accidentally interpolate payload data (Requirement 11.8): the
 * mapper returns one of these verbatim rather than anything derived from the
 * response body.
 */
export const ERROR_MESSAGES = {
  /** 400 whose body carried no usable validation summary (Requirement 11.2). */
  genericValidation: 'The submitted data failed validation. Please review your input and try again.',
  /** 401 — the session is gone; the user must re-authenticate (Requirement 11.6). */
  sessionExpired: 'Your session has expired. Please log in again.',
  /** 403 — authenticated but not allowed to perform the action (Requirement 11.7). */
  notPermitted: 'You are not permitted to perform this action.',
  /** 404 — the addressed ticket does not exist (Requirement 11.4). */
  ticketNotFound: 'The requested ticket could not be found.',
  /** 409 fallback when the backend supplied no conflict summary (Requirement 11.5). */
  statusChangeNotAllowed: 'The requested status change is not allowed.',
  /**
   * 500 and network failure — the single generic text, deliberately free of any
   * payload detail so no stack trace, SQL fragment, or file path can leak
   * (Requirement 11.8). Used for every 5xx and every network failure alike.
   */
  generic: 'Something went wrong. Please try again in a moment.',
} as const;

/**
 * Collapses an {@link ApiError} to the single, user-facing message its HTTP
 * status designates.
 *
 * This is a pure status → one-message table (Requirement 11.9): each failed
 * request yields exactly one string, chosen solely from the error's status and
 * — for the 400/409 arms — the backend summary the client already sanitised.
 * It never concatenates messages and never returns an empty string.
 *
 * Trust boundary: `ApiError.serverMessage` is `null` unless the client already
 * judged the backend text a short, single-line, safe summary; the raw payload
 * is unreachable from here. The 500 and network arms therefore ignore
 * `serverMessage` entirely and return a fixed constant, so a 500 body posing as
 * a summary still cannot leak (Requirement 11.8).
 *
 * @param error the normalised failure from {@link ApiError}
 * @returns exactly one message string to display
 */
export function errorMapper(error: ApiError): string {
  // A network failure has no status and is, to the user, indistinguishable from
  // a server fault: both get the same generic text (Requirement 11.8).
  if (error.isNetworkFailure || error.status === null) {
    return ERROR_MESSAGES.generic;
  }

  switch (error.status) {
    case 400:
      // The backend's own validation summary when it gave one, else generic
      // (Requirements 11.1, 11.2). `serverMessage` is already safe or null.
      return error.serverMessage ?? ERROR_MESSAGES.genericValidation;

    case 401:
      return ERROR_MESSAGES.sessionExpired;

    case 403:
      return ERROR_MESSAGES.notPermitted;

    case 404:
      return ERROR_MESSAGES.ticketNotFound;

    case 409:
      // The backend's 409 message names both the current and requested status;
      // pass it through when present, else a generic conflict message
      // (Requirements 8.7, 11.5).
      return error.serverMessage ?? ERROR_MESSAGES.statusChangeNotAllowed;

    default:
      // 5xx and any other unhandled status collapse to the leak-proof generic
      // text (Requirement 11.8). Covers 500 explicitly and fails closed for the
      // rest rather than surfacing an unmapped status.
      return ERROR_MESSAGES.generic;
  }
}
