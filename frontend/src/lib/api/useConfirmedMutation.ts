'use client';

import {
  useMutation,
  useQueryClient,
  type QueryKey,
  type UseMutationOptions,
  type UseMutationResult,
} from '@tanstack/react-query';
import { withoutOptimisticUpdate } from './queryClient';

export interface ConfirmedMutationOptions<TData, TError, TVariables>
  extends Omit<UseMutationOptions<TData, TError, TVariables, never>, 'onMutate'> {
  /**
   * Cache keys to invalidate *after* the backend confirms the write, so the
   * next render shows server-confirmed values rather than submitted ones
   * (Requirements 2.6, 4.7).
   */
  invalidates?: QueryKey[];
}

/**
 * The only sanctioned way to run a write in this app.
 *
 * It is a thin wrapper over `useMutation` that structurally cannot perform an
 * optimistic update: `onMutate` is excluded from its options type, so nothing
 * can write to the query cache while a submission is in flight. Callers read
 * `isPending` to show a "saving" affordance, but the values they render keep
 * coming from the last confirmed server response (Requirement 4.8).
 */
export function useConfirmedMutation<TData, TError = unknown, TVariables = void>(
  options: ConfirmedMutationOptions<TData, TError, TVariables>,
): UseMutationResult<TData, TError, TVariables, never> {
  const queryClient = useQueryClient();
  const { invalidates, onSuccess, ...rest } = options;

  return useMutation(
    withoutOptimisticUpdate<TData, TError, TVariables, never>({
      ...rest,
      onSuccess: async (data, variables, context) => {
        await Promise.all(
          (invalidates ?? []).map((queryKey) => queryClient.invalidateQueries({ queryKey })),
        );
        await onSuccess?.(data, variables, context);
      },
    }),
  );
}
