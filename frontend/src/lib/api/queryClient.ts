import { QueryClient, type UseMutationOptions } from '@tanstack/react-query';

/**
 * Requirement 4.8 forbids the UI from ever rendering a submitted-but-unconfirmed
 * value as a ticket's current value. Optimistic updates do exactly that: they
 * write the submitted payload into the query cache before the backend has
 * confirmed it, so every reader of that cache would display unconfirmed data.
 *
 * The whole cache-write-on-pending mechanism is therefore switched off by
 * convention *and* by the guard below, rather than being left to the discipline
 * of each individual mutation call site. Confirmed values only ever enter the
 * cache from a server response (Requirements 2.6, 4.7).
 */
export const OPTIMISTIC_UPDATE_GUARD_MESSAGE =
  'Optimistic updates are disabled: a mutation must not write to the query cache before the backend confirms it (Requirement 4.8). Invalidate or set query data in onSuccess instead.';

/**
 * The cache-mutating callbacks that would let a pending submission be rendered
 * as a confirmed value. `onMutate` is the documented optimistic-update hook;
 * `onError` rollbacks only exist to undo an optimistic write, so their presence
 * is the same smell.
 */
type ForbiddenMutationKeys = 'onMutate';

/**
 * Accepts mutation options only if they perform no pre-confirmation cache write.
 *
 * Enforced in the type system (so the mistake is a compile error) and at runtime
 * (so an untyped or dynamically built options object cannot slip past).
 */
export function withoutOptimisticUpdate<TData, TError, TVariables, TContext>(
  options: Omit<UseMutationOptions<TData, TError, TVariables, TContext>, ForbiddenMutationKeys>,
): UseMutationOptions<TData, TError, TVariables, TContext> {
  if ('onMutate' in options && (options as { onMutate?: unknown }).onMutate !== undefined) {
    throw new Error(OPTIMISTIC_UPDATE_GUARD_MESSAGE);
  }
  return options as UseMutationOptions<TData, TError, TVariables, TContext>;
}

/** Defaults shared by the browser client and by per-test clients. */
export const queryClientDefaults = {
  queries: {
    // A ticket list or detail is re-fetched on demand (after a confirmed write)
    // rather than being trusted indefinitely.
    staleTime: 30_000,
    gcTime: 5 * 60_000,
    // Retrying a 4xx cannot succeed and only delays the error message the user
    // needs to see (Requirement 11.x). Retry is handled per-query where a
    // transient failure is actually plausible.
    retry: false,
    refetchOnWindowFocus: false,
  },
  mutations: {
    // A non-idempotent POST must not be silently repeated.
    retry: false,
  },
} as const;

/**
 * Creates a `QueryClient` with optimistic updates disabled.
 *
 * A factory rather than a module-level singleton: the browser gets one instance
 * per app mount, and each test gets its own isolated cache.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { ...queryClientDefaults.queries },
      mutations: { ...queryClientDefaults.mutations },
    },
  });
}
