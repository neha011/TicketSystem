'use client';

import { isApiError } from '@/lib/api/apiClient';
import { errorMapper, ERROR_MESSAGES } from '@/lib/api/errorMapper';
import type { TicketDetailResponse } from '@/lib/api/types';
import { CommentForm } from './CommentForm';
import { CommentList } from './CommentList';
import { useTicketDetail } from './useTicketDetail';

/** Placeholder shown when a ticket has no assignee (mirrors the list view). */
const UNASSIGNED_LABEL = 'Unassigned';

function formatInstant(iso: string): string {
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? iso : parsed.toLocaleString();
}

/**
 * Renders every field of a loaded ticket (Requirement 3.1) plus its comment
 * thread and the comment composer.
 */
function TicketFields({ ticket }: { ticket: TicketDetailResponse }) {
  return (
    <>
      <h2 id="ticket-detail-heading">{ticket.title}</h2>
      <dl>
        <dt>Status</dt>
        <dd>{ticket.status}</dd>

        <dt>Priority</dt>
        <dd>{ticket.priority}</dd>

        <dt>Assignee</dt>
        <dd>
          {ticket.assignee === null || ticket.assignee.trim() === ''
            ? UNASSIGNED_LABEL
            : ticket.assignee}
        </dd>

        <dt>Description</dt>
        <dd style={{ whiteSpace: 'pre-wrap' }}>
          {ticket.description === null || ticket.description === '' ? (
            <span style={{ color: 'var(--color-muted)' }}>No description.</span>
          ) : (
            ticket.description
          )}
        </dd>

        <dt>Created</dt>
        <dd>
          <time dateTime={ticket.createdAt}>{formatInstant(ticket.createdAt)}</time>
        </dd>

        <dt>Last updated</dt>
        <dd>
          <time dateTime={ticket.updatedAt}>{formatInstant(ticket.updatedAt)}</time>
        </dd>
      </dl>

      <section aria-labelledby="comments-heading">
        <h3 id="comments-heading">Comments</h3>
        <CommentList comments={ticket.comments} />
        <CommentForm ticketId={ticket.id} />
      </section>
    </>
  );
}

/**
 * The ticket detail view.
 *
 * Exactly one of loading / error / loaded is rendered. On error the single
 * status-mapped message is shown (Requirement 11.9) and no ticket data leaks.
 * The loaded branch shows every field and the comment thread; a confirmed new
 * comment appears without a full page reload (Requirement 5.7).
 */
export function TicketDetailView({ ticketId }: { ticketId: string }) {
  const query = useTicketDetail(ticketId);

  if (query.isPending) {
    return <p role="status">Loading ticket…</p>;
  }

  if (query.isError) {
    const message = isApiError(query.error) ? errorMapper(query.error) : ERROR_MESSAGES.generic;
    return (
      <p role="alert" style={{ color: 'var(--color-error)' }}>
        {message}
      </p>
    );
  }

  return <TicketFields ticket={query.data} />;
}
