import { describe, expect, it } from 'vitest';
import { queryKeys } from './queryKeys';

describe('queryKeys', () => {
  it('should nest list keys under the invalidation root so one invalidation covers every page', () => {
    expect(queryKeys.ticketList({ page: 2 }).slice(0, 2)).toEqual(queryKeys.ticketLists());
  });

  it('should treat an omitted parameter and an explicit default as distinct cache entries', () => {
    expect(queryKeys.ticketList({})).not.toEqual(queryKeys.ticketList({ page: 0 }));
  });

  it('should produce equal keys for equivalent queries regardless of key order', () => {
    expect(queryKeys.ticketList({ page: 1, status: 'OPEN' })).toEqual(
      queryKeys.ticketList({ status: 'OPEN', page: 1 }),
    );
  });

  it('should scope detail keys by ticket id', () => {
    expect(queryKeys.ticketDetail('a')).not.toEqual(queryKeys.ticketDetail('b'));
  });
});
