'use client';

import { useState } from 'react';
import { apiClient, isApiError } from '@/lib/api/apiClient';
import { errorMapper } from '@/lib/api/errorMapper';
import { queryKeys } from '@/lib/api/queryKeys';
import { useConfirmedMutation } from '@/lib/api/useConfirmedMutation';
import type { TicketDetailResponse, TicketStatus } from '@/lib/api/types';
import { isTerminal, permittedTargets } from '@/lib/tickets/statusTransitions';

export interface StatusTransitionControlProps {
  /** The ticket whose status the control acts on; supplies id, status, version. */
  ticket: Pick<TicketDetailResponse, 'id' | 'status' | 'version'>;
}

/** Human-readable label for a status enum value. */
function statusLabel(status: TicketStatus): string {
  return status.replace(/_/g, ' ').toLowerCase();
}

/**
 * Offers the permitted status transitions for a ticket and applies the one the
 * user picks.
 *
 * Affordance rules (Req 8.1, 8.6): only the targets the client-side transition
 * table permits are offered, and the whole control is hidden for a terminal
 * status (CLOSED, CANCELLED) rather than shown empty. This mirror is an
 * affordance only — the server re-checks every transition, so a stale mirror
 * can at worst surface a 409 the mapper renders (Req 8.7).
 *
 * Outcome rules: a successful transition renders a confirmation naming the new
 * status (Req 8.10); a rejection renders the single mapped message, which for a
 * 409 names both the current and requested status because the backend supplies
 * that text and {@link errorMapper} passes it through (Req 8.7). The write goes
 * through {@link useConfirmedMutation}, so no submitted status is ever shown as
 * confirmed before the backend agrees (Req 4.8).
 */
export function StatusTransitionControl({ ticket }: StatusTransitionControlProps) {
  const targets = permittedTargets(ticket.status);

  const mutation = useConfirmedMutation<TicketDetailResponse, unknown, TicketStatus>({
    mutationFn: (target) =>
      apiClient.transitionTicketStatus(ticket.id, { status: target, version: ticket.version }),
    invalidates: [queryKeys.ticketDetail(ticket.id), queryKeys.ticketLists()],
  });

  // Track which target was last submitted so the confirmation can name the new
  // status. The confirmed value still comes from the server response.
  const [lastRequested, setLastRequested] = useState<TicketStatus | null>(null);

  // Hidden entirely for terminal states — no dead affordance (Req 8.6).
  if (isTerminal(ticket.status)) {
    return null;
  }

  function requestTransition(target: TicketStatus) {
    setLastRequested(target);
    mutation.mutate(target);
  }

  const confirmedStatus = mutation.isSuccess ? mutation.data.status : null;

  return (
    <section aria-labelledby="status-transition-heading">
      <h3 id="status-transition-heading">Change status</h3>
      <p>
        Current status: <strong>{statusLabel(ticket.status)}</strong>
      </p>

      <div role="group" aria-label="Permitted status transitions">
        {targets.map((target) => (
          <button
            key={target}
            type="button"
            disabled={mutation.isPending}
            onClick={() => requestTransition(target)}
          >
            Move to {statusLabel(target)}
          </button>
        ))}
      </div>

      {confirmedStatus !== null && (
        <p role="status">Status updated to {statusLabel(confirmedStatus)}.</p>
      )}

      {mutation.isError && (
        <p role="alert" style={{ color: 'var(--color-error)' }}>
          {isApiError(mutation.error)
            ? errorMapper(mutation.error)
            : lastRequested !== null
              ? `Could not change status from ${statusLabel(ticket.status)} to ${statusLabel(
                  lastRequested,
                )}.`
              : 'Something went wrong. Please try again in a moment.'}
        </p>
      )}
    </section>
  );
}
