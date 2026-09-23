import { describe, expect, it } from 'vitest';
import { ApiError, type ApiErrorKind } from './apiClient';
import { ERROR_MESSAGES, errorMapper } from './errorMapper';

/** Builds an ApiError for a given status without going through the network. */
function httpError(
  status: number,
  serverMessage: string | null = null,
  kind: ApiErrorKind = 'http',
): ApiError {
  return new ApiError({
    status,
    kind,
    serverMessage,
    fieldErrors: [],
    fallbackMessage: `The ticket service responded with status ${status}.`,
  });
}

function networkError(): ApiError {
  return new ApiError({
    status: null,
    kind: 'network',
    serverMessage: null,
    fieldErrors: [],
    fallbackMessage: 'The request did not reach the ticket service.',
  });
}

describe('errorMapper', () => {
  it('should return the backend validation message for a 400 that carries one', () => {
    const error = httpError(400, 'Validation failed for 2 field(s)');

    expect(errorMapper(error)).toBe('Validation failed for 2 field(s)');
  });

  it('should return the generic validation message for a 400 without a message', () => {
    const error = httpError(400, null);

    expect(errorMapper(error)).toBe(ERROR_MESSAGES.genericValidation);
  });

  it('should return the session-expired message for a 401', () => {
    expect(errorMapper(httpError(401))).toBe(ERROR_MESSAGES.sessionExpired);
  });

  it('should return the not-permitted message for a 403', () => {
    expect(errorMapper(httpError(403))).toBe(ERROR_MESSAGES.notPermitted);
  });

  it('should return the not-found message for a 404', () => {
    expect(errorMapper(httpError(404))).toBe(ERROR_MESSAGES.ticketNotFound);
  });

  it('should pass through the backend 409 message naming both statuses', () => {
    const conflict = 'Cannot move ticket from CLOSED to OPEN.';
    const error = httpError(409, conflict);

    expect(errorMapper(error)).toBe(conflict);
  });

  it('should fall back to a generic conflict message for a 409 without a message', () => {
    expect(errorMapper(httpError(409))).toBe(ERROR_MESSAGES.statusChangeNotAllowed);
  });

  it('should return the fixed generic message for a 500', () => {
    expect(errorMapper(httpError(500))).toBe(ERROR_MESSAGES.generic);
  });

  it('should return the fixed generic message for a network failure', () => {
    expect(errorMapper(networkError())).toBe(ERROR_MESSAGES.generic);
  });

  it('should not leak a 500 body that poses as a usable summary', () => {
    // Even if a short single-line message survived into serverMessage on a 500,
    // the mapper must ignore it and return only the fixed generic text.
    const leaky = httpError(500, 'PSQLException: relation ticket does not exist');

    const message = errorMapper(leaky);

    expect(message).toBe(ERROR_MESSAGES.generic);
    expect(message).not.toContain('PSQLException');
  });

  it('should return exactly one message (never empty, never stacked) for every failure', () => {
    const errors = [
      httpError(400, 'x'),
      httpError(400, null),
      httpError(401),
      httpError(403),
      httpError(404),
      httpError(409, 'from A to B'),
      httpError(409, null),
      httpError(500),
      networkError(),
    ];

    for (const error of errors) {
      const message = errorMapper(error);
      expect(typeof message).toBe('string');
      expect(message.length).toBeGreaterThan(0);
    }
  });
});
