'use client';

import { isApiError } from '@/lib/api/apiClient';
import { errorMapper } from '@/lib/api/errorMapper';

/**
 * The single slot in which a failed request's message is shown.
 *
 * Every failure is routed through {@link errorMapper}, which returns exactly one
 * status-specific string (Req 11.9) and never leaks internal detail (Req 11.8).
 * A non-{@link ApiError} value (an unexpected throw) collapses to the same
 * generic text an unknown status would, so callers never render a raw message.
 */
export function ErrorBanner({ error }: { error: unknown }) {
  if (error === null || error === undefined) {
    return null;
  }

  const message = isApiError(error)
    ? errorMapper(error)
    : 'Something went wrong. Please try again in a moment.';

  return (
    <p role="alert" style={{ color: 'var(--color-error)' }}>
      {message}
    </p>
  );
}
