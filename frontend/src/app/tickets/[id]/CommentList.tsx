'use client';

import type { CommentResponse } from '@/lib/api/types';
import { sortComments } from './sortComments';

/** Renders an ISO-8601 instant as a locale string, falling back to the raw value. */
function formatInstant(iso: string): string {
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? iso : parsed.toLocaleString();
}

/**
 * The comment thread for a ticket.
 *
 * Comments render oldest → newest by `(createdAt, id)` (Requirements 3.6, 5.7)
 * via the pure {@link sortComments}. The no-comments indication is shown *only*
 * when the collection is empty, and the list is shown otherwise — never both
 * (Requirements 3.7, 3.8).
 */
export function CommentList({ comments }: { comments: readonly CommentResponse[] }) {
  if (comments.length === 0) {
    return (
      <p data-testid="no-comments">No comments yet.</p>
    );
  }

  const ordered = sortComments(comments);

  return (
    <ol aria-label="Comments" style={{ listStyle: 'none', padding: 0 }}>
      {ordered.map((comment) => (
        <li key={comment.id} style={{ borderTop: '1px solid var(--color-border)', padding: '0.5rem 0' }}>
          <p style={{ margin: 0 }}>
            <strong>{comment.author}</strong>{' '}
            <time dateTime={comment.createdAt} style={{ color: 'var(--color-muted)' }}>
              {formatInstant(comment.createdAt)}
            </time>
          </p>
          {/* Rendered as text, never as HTML, so stored content cannot execute. */}
          <p style={{ margin: '0.25rem 0 0', whiteSpace: 'pre-wrap' }}>{comment.content}</p>
        </li>
      ))}
    </ol>
  );
}
