'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ApiError, apiClient, isApiError } from '@/lib/api/apiClient';
import { queryKeys } from '@/lib/api/queryKeys';
import { TICKET_STATUSES, type TicketListQuery, type TicketStatus } from '@/lib/api/types';
import {
  assigneeDisplay,
  selectListViewState,
  validateKeyword,
  type ListQueryResult,
} from '@/lib/tickets/listViewState';

/** Server-side list default; kept here so pagination maths lines up with it. */
const DEFAULT_PAGE_SIZE = 20;

/**
 * The ticket list view: search, status filter, pagination, and a row per
 * ticket showing title, status, priority, and assignee (Requirements 2.6–2.9,
 * 6.3, 6.4, 7.1).
 *
 * All read state is derived from a single TanStack Query; the view never writes
 * to the cache, so a pending fetch can never be shown as confirmed data. The
 * loading / error / empty / populated decision is delegated to the pure
 * {@link selectListViewState} so exactly one branch renders.
 */
export function TicketListView() {
  // The applied query is what actually drives fetching; the search box is a
  // separate draft so keystrokes do not fire a request per character.
  const [query, setQuery] = useState<TicketListQuery>({ page: 0, size: DEFAULT_PAGE_SIZE });
  const [keywordDraft, setKeywordDraft] = useState('');
  const [keywordError, setKeywordError] = useState<string | null>(null);

  const listQuery = useQuery({
    queryKey: queryKeys.ticketList(query),
    queryFn: ({ signal }) => apiClient.listTickets(query, { signal }),
  });

  const result: ListQueryResult = useMemo(() => {
    if (listQuery.isError) {
      // The query function only ever rejects with an ApiError (apiClient
      // normalises everything); fall back defensively just in case.
      const error: ApiError = isApiError(listQuery.error)
        ? listQuery.error
        : new ApiError({
            status: null,
            kind: 'network',
            serverMessage: null,
            fieldErrors: [],
            fallbackMessage: 'The request did not reach the ticket service.',
          });
      return { status: 'error', error };
    }
    if (listQuery.data !== undefined) {
      return { status: 'success', data: listQuery.data };
    }
    return { status: 'pending' };
  }, [listQuery.isError, listQuery.error, listQuery.data]);

  const viewState = selectListViewState(result);

  function applyKeyword() {
    const message = validateKeyword(keywordDraft);
    setKeywordError(message);
    if (message !== null) {
      // Invalid length: do not issue a request. The backend would reject it
      // (Req 6.4); showing the range message locally is the responsive path.
      return;
    }
    const trimmedPresent = keywordDraft.length > 0;
    setQuery((prev) => ({
      ...prev,
      page: 0,
      ...(trimmedPresent ? { keyword: keywordDraft } : { keyword: undefined }),
    }));
  }

  function onKeywordSubmit(event: React.FormEvent) {
    event.preventDefault();
    applyKeyword();
  }

  function onStatusChange(value: string) {
    const status = value === '' ? undefined : (value as TicketStatus);
    setQuery((prev) => ({ ...prev, page: 0, status }));
  }

  function goToPage(page: number) {
    setQuery((prev) => ({ ...prev, page }));
  }

  return (
    <section aria-labelledby="ticket-list-heading">
      <h2 id="ticket-list-heading">All tickets</h2>

      <p>
        <Link href="/tickets/new">Create a ticket</Link>
      </p>

      <div className="ticket-list-controls">
        <form role="search" onSubmit={onKeywordSubmit} aria-label="Search tickets">
          <label htmlFor="ticket-search">Search</label>
          <input
            id="ticket-search"
            type="search"
            name="keyword"
            value={keywordDraft}
            onChange={(event) => setKeywordDraft(event.target.value)}
            aria-invalid={keywordError !== null}
            aria-describedby={keywordError !== null ? 'ticket-search-error' : undefined}
          />
          <button type="submit">Search</button>
          {keywordError !== null ? (
            <p id="ticket-search-error" role="alert" className="field-error">
              {keywordError}
            </p>
          ) : null}
        </form>

        <div className="status-filter">
          <label htmlFor="status-filter">Status</label>
          <select
            id="status-filter"
            name="status"
            value={query.status ?? ''}
            onChange={(event) => onStatusChange(event.target.value)}
          >
            <option value="">All statuses</option>
            {TICKET_STATUSES.map((status) => (
              <option key={status} value={status}>
                {status}
              </option>
            ))}
          </select>
        </div>
      </div>

      {viewState.kind === 'loading' ? (
        <p role="status">Loading tickets…</p>
      ) : null}

      {viewState.kind === 'error' ? (
        <p role="alert" className="error-banner">
          {viewState.message}
        </p>
      ) : null}

      {viewState.kind === 'empty' ? (
        <p role="status">No tickets found.</p>
      ) : null}

      {viewState.kind === 'populated' ? (
        <>
          <table>
            <caption className="visually-hidden">Tickets</caption>
            <thead>
              <tr>
                <th scope="col">Title</th>
                <th scope="col">Status</th>
                <th scope="col">Priority</th>
                <th scope="col">Assignee</th>
              </tr>
            </thead>
            <tbody>
              {viewState.tickets.map((ticket) => (
                <tr key={ticket.id}>
                  <td>
                    <Link href={`/tickets/${ticket.id}`}>{ticket.title}</Link>
                  </td>
                  <td>{ticket.status}</td>
                  <td>{ticket.priority}</td>
                  <td>{assigneeDisplay(ticket.assignee)}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <nav aria-label="Pagination" className="pagination">
            <button
              type="button"
              onClick={() => goToPage(viewState.page.page - 1)}
              disabled={viewState.page.page <= 0}
            >
              Previous
            </button>
            <span aria-live="polite">
              Page {viewState.page.page + 1} of {Math.max(viewState.page.totalPages, 1)}
            </span>
            <button
              type="button"
              onClick={() => goToPage(viewState.page.page + 1)}
              disabled={viewState.page.page + 1 >= viewState.page.totalPages}
            >
              Next
            </button>
          </nav>
        </>
      ) : null}
    </section>
  );
}
