import type { TicketListQuery } from './types';

/**
 * Centralised TanStack Query cache keys.
 *
 * Keys are declared in one place so that a mutation invalidating "the ticket
 * list" cannot drift out of sync with the key a list query actually registered
 * under. Every key is a plain, serialisable tuple.
 */
export const queryKeys = {
  tickets: ['tickets'] as const,

  /** All list queries, regardless of page/filter — the invalidation root. */
  ticketLists: () => [...queryKeys.tickets, 'list'] as const,

  /** One specific page/filter combination. */
  ticketList: (query: TicketListQuery) =>
    [
      ...queryKeys.ticketLists(),
      {
        page: query.page ?? null,
        size: query.size ?? null,
        keyword: query.keyword ?? null,
        status: query.status ?? null,
      },
    ] as const,

  /** A single ticket's detail, including its comments. */
  ticketDetail: (id: string) => [...queryKeys.tickets, 'detail', id] as const,
} as const;
