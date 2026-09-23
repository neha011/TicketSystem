/* eslint-disable no-restricted-syntax -- these tests must construct the
   forbidden `onMutate` option in order to prove the guard rejects it. */
import { describe, expect, it } from 'vitest';
import {
  createQueryClient,
  OPTIMISTIC_UPDATE_GUARD_MESSAGE,
  withoutOptimisticUpdate,
} from './queryClient';

describe('createQueryClient', () => {
  it('should disable retries so a failed write is surfaced rather than silently repeated', () => {
    const defaults = createQueryClient().getDefaultOptions();

    expect(defaults.queries?.retry).toBe(false);
    expect(defaults.mutations?.retry).toBe(false);
  });

  it('should return an isolated cache per call so callers never share state', () => {
    const first = createQueryClient();
    const second = createQueryClient();

    first.setQueryData(['tickets'], 'from-first');

    expect(second.getQueryData(['tickets'])).toBeUndefined();
  });
});

describe('withoutOptimisticUpdate', () => {
  it('should pass through options that perform no pre-confirmation cache write', () => {
    const options = { mutationFn: async () => 'ok' };

    expect(withoutOptimisticUpdate(options)).toBe(options);
  });

  it('should reject an onMutate callback, which is how optimistic updates enter the cache', () => {
    const smuggled = { mutationFn: async () => 'ok', onMutate: () => undefined };

    expect(() => withoutOptimisticUpdate(smuggled as never)).toThrowError(
      OPTIMISTIC_UPDATE_GUARD_MESSAGE,
    );
  });

  it('should accept an explicitly undefined onMutate rather than treating the key as present', () => {
    const options = { mutationFn: async () => 'ok', onMutate: undefined };

    expect(() => withoutOptimisticUpdate(options as never)).not.toThrow();
  });
});
