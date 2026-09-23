'use client';

import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@/lib/api/apiClient';
import { queryKeys } from '@/lib/api/queryKeys';
import type { TicketDetailResponse } from '@/lib/api/types';
import { EditTicketForm } from './EditTicketForm';
import { ErrorBanner } from './ErrorBanner';
import { StatusTransitionControl } from './StatusTransitionControl';

export interface TicketEditAreaProps {
  ticketId: string;
}

/**
 * The editing surface for a single ticket: the field-edit form and the status
 * transition control (task 17.5).
 *
 * It loads the last backend-confirmed ticket through TanStack Query and hands
 * that confirmed value to both children. Because writes run through
 * `useConfirmedMutation`, which invalidates this exact query on success, a
 * confirmed edit or transition re-fetches here and the confirmed values refresh
 * in place — no full page reload (Req 4.7). While a write is in flight the
 * children keep rendering these confirmed values, never the submitted ones
 * (Req 4.8).
 *
 * The full read-only detail view and comment list are a separate concern
 * (task 17.3); this component owns only the create/edit/transition affordances.
 */
export function TicketEditArea({ ticketId }: TicketEditAreaProps) {
  const query = useQuery<TicketDetailResponse>({
    queryKey: queryKeys.ticketDetail(ticketId),
    queryFn: ({ signal }) => apiClient.getTicket(ticketId, { signal }),
  });

  if (query.isPending) {
    return <p role="status">Loading ticket…</p>;
  }

  if (query.isError) {
    return <ErrorBanner error={query.error} />;
  }

  return (
    <div>
      <StatusTransitionControl ticket={query.data} />
      <EditTicketForm ticket={query.data} />
    </div>
  );
}
