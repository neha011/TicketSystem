'use client';

import { useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { apiClient } from '@/lib/api/apiClient';
import { queryKeys } from '@/lib/api/queryKeys';
import type { CommentResponse, CreateCommentRequest, TicketDetailResponse } from '@/lib/api/types';
import { useConfirmedMutation } from '@/lib/api/useConfirmedMutation';

/**
 * Loads one ticket's full detail, including its comments (Requirement 3.1).
 *
 * The query key is the shared `ticketDetail(id)` so a confirmed comment write
 * updates exactly the cache entry this view reads from.
 */
export function useTicketDetail(id: string): UseQueryResult<TicketDetailResponse> {
  return useQuery({
    queryKey: queryKeys.ticketDetail(id),
    queryFn: ({ signal }) => apiClient.getTicket(id, { signal }),
  });
}

/**
 * Adds a comment to a ticket and, on backend confirmation, folds the returned
 * comment into the detail cache so it appears without a full page reload
 * (Requirement 5.7).
 *
 * Optimistic updates stay off: nothing is written to the cache until the
 * backend confirms the comment (`onSuccess` only). The value merged in is the
 * server's own `CommentResponse` — the id, author, and timestamp it assigned —
 * never the submitted text, so a pending submission is never rendered as a
 * confirmed comment (Requirement 4.8). The detail query is also invalidated so
 * the next fetch reconciles against the source of truth.
 */
export function useAddComment(id: string) {
  const queryClient = useQueryClient();

  return useConfirmedMutation<CommentResponse, unknown, CreateCommentRequest>({
    mutationFn: (body) => apiClient.addComment(id, body),
    invalidates: [queryKeys.ticketDetail(id)],
    onSuccess: (created) => {
      queryClient.setQueryData<TicketDetailResponse>(queryKeys.ticketDetail(id), (current) => {
        if (current === undefined) {
          return current;
        }
        // Guard against a double-append if the same confirmed comment arrives
        // twice (e.g. a rapid resubmit): merge by id.
        if (current.comments.some((comment: CommentResponse) => comment.id === created.id)) {
          return current;
        }
        return { ...current, comments: [...current.comments, created] };
      });
    },
  });
}
